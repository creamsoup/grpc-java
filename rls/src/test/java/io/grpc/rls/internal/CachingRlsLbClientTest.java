package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Helper;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.TimeProvider;
import io.grpc.rls.internal.CachingRlsLbClient.CacheEntry;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.internal.LruCache.EvictionListener;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.internal.RlsProtoData.RouteLookupRequest;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class CachingRlsLbClientTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener;
  @Mock
  private Helper helper;
  @Mock
  private Throttler throttler;
  @Mock
  private SocketAddress socketAddress;

  private final FakeClock fakeClock = new FakeClock();
  private final TimeProvider fakeTimeProvider = fakeClock.getTimeProvider();
  private final FakeBackoffProvider fakeBackoffProvider = new FakeBackoffProvider();
  private final ChildLbResolvedAddressFactory childLbResolvedAddressFactory =
      new ChildLbResolvedAddressFactory(
          ImmutableList.of(new EquivalentAddressGroup(socketAddress)),
          Attributes.EMPTY);
  private CachingRlsLbClient rlsLbClient;

  @Before
  public void setUp() throws Exception {
    RouteLookupConfig routeLookupConfig = null;
    ChildLoadBalancingPolicy childLbPolicy = null;
    LbPolicyConfiguration lbPolicyConfiguration =
        new LbPolicyConfiguration(routeLookupConfig, childLbPolicy);
    rlsLbClient =
        CachingRlsLbClient.newBuilder()
            .setBackoffProvider(fakeBackoffProvider)
            .setChildLbResolvedAddressesFactory(childLbResolvedAddressFactory)
            .setEvictionListener(evictionListener)
            .setHelper(helper)
            .setLbPolicyConfig(lbPolicyConfiguration)
            .setThrottler(throttler)
            .setTimeProvider(fakeTimeProvider)
            .build();
  }

  private static class FakeBackoffProvider implements BackoffPolicy.Provider {

    private final AtomicReference<BackoffPolicy> nextPolicy = new AtomicReference<>();

    @Override
    public BackoffPolicy get() {
      return nextPolicy.get();
    }

    void setNextPolicy(BackoffPolicy policy) {
      nextPolicy.set(checkNotNull(policy, "policy"));
    }
  }
}