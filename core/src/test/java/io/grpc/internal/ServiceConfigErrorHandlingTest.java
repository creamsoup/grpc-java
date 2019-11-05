/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ClientInterceptor;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.Status;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for ServiceConfig error handling. */
@RunWith(JUnit4.class)
public class ServiceConfigErrorHandlingTest {

  private static final int DEFAULT_PORT = 447;
  private static final long RECONNECT_BACKOFF_INTERVAL_NANOS = 10;
  private static final String SERVICE_NAME = "fake.example.com";
  private static final String USER_AGENT = "userAgent";
  private static final String TARGET = "fake://" + SERVICE_NAME;
  private static final String MOCK_POLICY_NAME = "mock_lb";
  private URI expectedUri;
  private final SocketAddress socketAddress =
      new SocketAddress() {
        @Override
        public String toString() {
          return "test-addr";
        }
      };
  private final EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);
  private final FakeClock timer = new FakeClock();
  private final FakeClock executor = new FakeClock();
  private static final FakeClock.TaskFilter NAME_RESOLVER_REFRESH_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(
              ManagedChannelImpl.DelayedNameResolverRefresh.class.getName());
        }
      };

  private final InternalChannelz channelz = new InternalChannelz();

  @Rule public final ExpectedException thrown = ExpectedException.none();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ManagedChannelImpl channel;

  private FakeLoadBalancer mockLoadBalancer =
      mock(FakeLoadBalancer.class, delegatesTo(new FakeLoadBalancer()));

  private final LoadBalancerProvider mockLoadBalancerProvider =
      mock(LoadBalancerProvider.class, delegatesTo(new LoadBalancerProvider() {
        @Override
        public LoadBalancer newLoadBalancer(final Helper helper) {
          mockLoadBalancer.setHelper(helper);
          return mockLoadBalancer;
        }

        @Override
        public boolean isAvailable() {
          return true;
        }

        @Override
        public int getPriority() {
          return 999;
        }

        @Override
        public String getPolicyName() {
          return MOCK_POLICY_NAME;
        }
      }));

  @Mock
  private ClientTransportFactory mockTransportFactory;
  @Mock
  private ObjectPool<Executor> executorPool;
  @Mock
  private ObjectPool<Executor> balancerRpcExecutorPool;
  @Mock
  private Executor blockingExecutor;
  private ChannelBuilder channelBuilder;

  private void createChannel(ClientInterceptor... interceptors) {
    checkState(channel == null);

    channel =
        new ManagedChannelImpl(
            channelBuilder,
            mockTransportFactory,
            new FakeBackoffPolicyProvider(),
            balancerRpcExecutorPool,
            timer.getStopwatchSupplier(),
            Arrays.asList(interceptors),
            timer.getTimeProvider());

    int numExpectedTasks = 0;

    // Force-exit the initial idle-mode
    channel.syncContext.execute(new Runnable() {
      @Override
      public void run() {
        channel.exitIdleMode();
      }
    });
    if (channelBuilder.idleTimeoutMillis != ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE) {
      numExpectedTasks += 1;
    }

    if (getNameResolverRefresh() != null) {
      numExpectedTasks += 1;
    }

    assertEquals(numExpectedTasks, timer.numPendingTasks());

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(null);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
  }

  @Before
  public void setUp() throws Exception {
    when(mockLoadBalancer.canHandleEmptyAddressListFromNameResolution()).thenCallRealMethod();
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    expectedUri = new URI(TARGET);
    when(mockTransportFactory.getScheduledExecutorService())
        .thenReturn(timer.getScheduledExecutorService());
    when(executorPool.getObject()).thenReturn(executor.getScheduledExecutorService());

    channelBuilder =
        new ChannelBuilder()
            .nameResolverFactory(new FakeNameResolverFactory.Builder(expectedUri).build())
            .defaultLoadBalancingPolicy(MOCK_POLICY_NAME)
            .userAgent(USER_AGENT)
            .idleTimeout(
                AbstractManagedChannelImplBuilder.IDLE_MODE_MAX_TIMEOUT_DAYS, TimeUnit.DAYS)
            .blockingExecutor(blockingExecutor);
    channelBuilder.executorPool = executorPool;
    channelBuilder.binlog = null;
    channelBuilder.channelz = channelz;
  }

  @After
  public void allPendingTasksAreRun() throws Exception {
    // The "never" verifications in the tests only hold up if all due tasks are done.
    // As for timer, although there may be scheduled tasks in a future time, since we don't test
    // any time-related behavior in this test suite, we only care the tasks that are due. This
    // would ignore any time-sensitive tasks, e.g., back-off and the idle timer.
    assertTrue(timer.getDueTasks() + " should be empty", timer.getDueTasks().isEmpty());
    assertEquals(executor.getPendingTasks() + " should be empty", 0, executor.numPendingTasks());
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
    }
  }

  @After
  public void cleanUp() {
    LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
  }

  @Test
  public void emptyAddressesWithValidConfig_lbNeedsAddress() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.<EquivalentAddressGroup>emptyList())
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);

    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(
                GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG,
                ImmutableMap.<String, Object>of("loadBalancingPolicy", "round_robin"))
            .build();
    nameResolverFactory.nextResolvedAttributes.set(serviceConfigAttrs);

    createChannel();

    assertThat(channel.getState(true)).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void emptyAddressesWithValidConfig_lbDoesNotNeedAddress() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.<EquivalentAddressGroup>emptyList())
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    when(mockLoadBalancer.canHandleEmptyAddressListFromNameResolution()).thenReturn(true);

    ImmutableMap<String, Object> serviceConfig =
        ImmutableMap.<String, Object>of("loadBalancingPolicy", "mock_lb");
    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(
                GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG,
                serviceConfig)
            .build();
    nameResolverFactory.nextResolvedAttributes.set(serviceConfigAttrs);

    createChannel();

    ArgumentCaptor<ResolvedAddresses> resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(mockLoadBalancer).handleResolvedAddresses(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses()).isEmpty();
    Attributes actualAttrs = resultCaptor.getValue().getAttributes();
    assertThat(actualAttrs.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG))
        .isEqualTo(serviceConfig);
    verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));

    assertThat(channel.getState(false)).isNotEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void validConfig_lbDoesNotNeedAddress() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);

    ImmutableMap<String, Object> serviceConfig =
        ImmutableMap.<String, Object>of("loadBalancingPolicy", "mock_lb");
    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(
                GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG,
                serviceConfig)
            .build();
    nameResolverFactory.nextResolvedAttributes.set(serviceConfigAttrs);

    createChannel();

    ArgumentCaptor<ResolvedAddresses> resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(mockLoadBalancer).handleResolvedAddresses(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
    Attributes actualAttrs = resultCaptor.getValue().getAttributes();
    assertThat(actualAttrs.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG))
        .isEqualTo(serviceConfig);
    verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));

    assertThat(channel.getState(false)).isNotEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void invalidConfig_noDefaultConfig() throws Exception {
    Status error = Status.NOT_FOUND.withDescription("service config error");
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup))
            .setError(error)
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);

    createChannel();

    ArgumentCaptor<Status> statusCaptor =
        ArgumentCaptor.forClass(Status.class);
    verify(mockLoadBalancer).handleNameResolutionError(statusCaptor.capture());
    assertThat(statusCaptor.getValue()).isEqualTo(error);

    assertThat(channel.getState(true)).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void resolverReturnsNoConfig_noDefaultConfig() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    nameResolverFactory.nextResolvedAttributes.set(Attributes.EMPTY);

    createChannel();

    ArgumentCaptor<ResolvedAddresses> resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(mockLoadBalancer).handleResolvedAddresses(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
    Attributes actualAttrs = resultCaptor.getValue().getAttributes();
    assertThat(actualAttrs.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG)).isEmpty();
    verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));

    assertThat(channel.getState(false)).isNotEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void resolverReturnsNoConfig_usingDefaultConfig() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    ImmutableMap<String, Object> defaultServiceConfig =
        ImmutableMap.<String, Object>of("loadBalancingPolicy", "mock_lb");
    channelBuilder.defaultServiceConfig(defaultServiceConfig);

    Attributes serviceConfigAttrs = Attributes.EMPTY;
    nameResolverFactory.nextResolvedAttributes.set(serviceConfigAttrs);

    createChannel();
    ArgumentCaptor<ResolvedAddresses> resultCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(mockLoadBalancer).handleResolvedAddresses(resultCaptor.capture());
    assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
    Attributes actualAttrs = resultCaptor.getValue().getAttributes();
    assertThat(actualAttrs.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG))
        .isEqualTo(defaultServiceConfig);
    verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    assertThat(channel.getState(false)).isNotEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void resolverReturnsInvalidConfig_firstResolutionWithDefaultConfig() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    ImmutableMap<String, Object> defaultServiceConfig =
        ImmutableMap.<String, Object>of("loadBalancingPolicy", "mock_lb");
    channelBuilder.defaultServiceConfig(defaultServiceConfig);

    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(
                GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG,
                ImmutableMap.<String, Object>of("loadBalancingPolicy", "kaboom"))
            .build();
    nameResolverFactory.nextResolvedAttributes.set(serviceConfigAttrs);

    createChannel();

    assertThat(channel.getState(false)).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void resolverReturnsInvalidConfig_firstResolutionWithoutDefaultConfig() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);

    Attributes serviceConfigAttrs =
        Attributes.newBuilder()
            .set(
                GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG,
                ImmutableMap.<String, Object>of("loadBalancingPolicy", "kaboom"))
            .build();
    nameResolverFactory.nextResolvedAttributes.set(serviceConfigAttrs);

    createChannel();

    assertThat(channel.getState(false)).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  private static final class ChannelBuilder
      extends AbstractManagedChannelImplBuilder<ChannelBuilder> {

    ChannelBuilder() {
      super(TARGET);
    }

    @Override protected ClientTransportFactory buildTransportFactory() {
      throw new UnsupportedOperationException();
    }

    @Override protected int getDefaultPort() {
      return DEFAULT_PORT;
    }
  }

  private static final class FakeBackoffPolicyProvider implements BackoffPolicy.Provider {
    @Override
    public BackoffPolicy get() {
      return new BackoffPolicy() {
        int multiplier = 1;

        @Override
        public long nextBackoffNanos() {
          return RECONNECT_BACKOFF_INTERVAL_NANOS * multiplier++;
        }
      };
    }
  }

  private static final class FakeNameResolverFactory extends NameResolver.Factory {
    final URI expectedUri;
    final List<EquivalentAddressGroup> servers;
    final boolean resolvedAtStart;
    final Status error;
    final ArrayList<FakeNameResolver> resolvers = new ArrayList<>();
    // The Attributes argument of the next invocation of listener.onAddresses(servers, attrs)
    final AtomicReference<Attributes> nextResolvedAttributes =
        new AtomicReference<>(Attributes.EMPTY);

    FakeNameResolverFactory(
        URI expectedUri,
        List<EquivalentAddressGroup> servers,
        boolean resolvedAtStart,
        Status error) {
      this.expectedUri = expectedUri;
      this.servers = servers;
      this.resolvedAtStart = resolvedAtStart;
      this.error = error;
    }

    @Override
    public NameResolver newNameResolver(final URI targetUri, NameResolver.Args args) {
      if (!expectedUri.equals(targetUri)) {
        return null;
      }
      assertEquals(DEFAULT_PORT, args.getDefaultPort());
      FakeNameResolver resolver = new FakeNameResolver(error);
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "fake";
    }

    void allResolved() {
      for (FakeNameResolver resolver : resolvers) {
        resolver.resolved();
      }
    }

    final class FakeNameResolver extends NameResolver {
      Listener2 listener;
      boolean shutdown;
      int refreshCalled;
      Status error;

      FakeNameResolver(Status error) {
        this.error = error;
      }

      @Override public String getServiceAuthority() {
        return expectedUri.getAuthority();
      }

      @Override public void start(Listener2 listener) {
        this.listener = listener;
        if (resolvedAtStart) {
          resolved();
        }
      }

      @Override public void refresh() {
        refreshCalled++;
        resolved();
      }

      void resolved() {
        if (error != null) {
          listener.onError(error);
          return;
        }
        listener.onResult(
            ResolutionResult.newBuilder()
                .setAddresses(servers)
                .setAttributes(nextResolvedAttributes.get())
                .build());
      }

      @Override public void shutdown() {
        shutdown = true;
      }

      @Override
      public String toString() {
        return "FakeNameResolver";
      }
    }

    static final class Builder {
      final URI expectedUri;
      List<EquivalentAddressGroup> servers = ImmutableList.of();
      boolean resolvedAtStart = true;
      Status error = null;

      Builder(URI expectedUri) {
        this.expectedUri = expectedUri;
      }

      Builder setServers(List<EquivalentAddressGroup> servers) {
        this.servers = servers;
        return this;
      }

      Builder setResolvedAtStart(boolean resolvedAtStart) {
        this.resolvedAtStart = resolvedAtStart;
        return this;
      }

      Builder setError(Status error) {
        this.error = error;
        return this;
      }

      FakeNameResolverFactory build() {
        return new FakeNameResolverFactory(expectedUri, servers, resolvedAtStart, error);
      }
    }
  }

  private FakeClock.ScheduledTask getNameResolverRefresh() {
    return Iterables.getOnlyElement(timer.getPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER), null);
  }

  private static class FakeLoadBalancer extends LoadBalancer {

    @Nullable
    private Helper helper;

    public void setHelper(Helper helper) {
      this.helper = helper;
    }

    @Override
    public void handleNameResolutionError(final Status error) {
      helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE,
          new SubchannelPicker() {
            @Override
            public PickResult pickSubchannel(PickSubchannelArgs args) {
              return PickResult.withError(error);
            }
          });
    }

    @Override
    public void shutdown() {}
  }
}
