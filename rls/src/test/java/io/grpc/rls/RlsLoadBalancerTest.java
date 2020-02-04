/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Server;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceBlockingStub;
import io.grpc.rls.RlsProtoConverters.RouteLookupRequestConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RlsLoadBalancerTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private final LoadBalancerProvider provider = new RlsLoadBalancerProvider();
  private Helper helper = mock(Helper.class);
  private LoadBalancer rlslb;

  private static final String INPROC_RLS_SERVER = "inproc-rls-server";
  private static final String ROUTE_LOOKUP_CONFIG = "{\n"
      + "  \"grpcKeyBuilders\": [\n"
      + "    {\n"
      + "      \"names\": [\n"
      + "        {\n"
      + "          \"service\": \"service1\",\n"
      + "          \"method\": \"create\"\n"
      + "        }\n"
      + "      ],\n"
      + "      \"headers\": [\n"
      + "        {\n"
      + "          \"key\": \"user\","
      + "          \"names\": [\"User\", \"Parent\"],\n"
      + "          \"optional\": true\n"
      + "        },\n"
      + "        {\n"
      + "          \"key\": \"id\","
      + "          \"names\": [\"X-Google-Id\"],\n"
      + "          \"optional\": true\n"
      + "        }\n"
      + "      ]\n"
      + "    },\n"
      + "    {\n"
      + "      \"names\": [\n"
      + "        {\n"
      + "          \"service\": \"service1\",\n"
      + "          \"method\": \"*\"\n"
      + "        }\n"
      + "      ],\n"
      + "      \"headers\": [\n"
      + "        {\n"
      + "          \"key\": \"user\","
      + "          \"names\": [\"User\", \"Parent\"],\n"
      + "          \"optional\": true\n"
      + "        },\n"
      + "        {\n"
      + "          \"key\": \"password\","
      + "          \"names\": [\"Password\"],\n"
      + "          \"optional\": true\n"
      + "        }\n"
      + "      ]\n"
      + "    },\n"
      + "    {\n"
      + "      \"names\": [\n"
      + "        {\n"
      + "          \"service\": \"service3\",\n"
      + "          \"method\": \"*\"\n"
      + "        }\n"
      + "      ],\n"
      + "      \"headers\": ["
      + "        {\n"
      + "          \"key\": \"user\","
      + "          \"names\": [\"User\", \"Parent\"],\n"
      + "          \"optional\": true\n"
      + "        }\n"
      + "      ]\n"
      + "    }\n"
      + "  ],\n"
      + "  \"lookupService\": \"service1\",\n"
      + "  \"lookupServiceTimeout\": 2,\n"
      + "  \"maxAge\": 300,\n"
      + "  \"staleAge\": 240,\n"
      + "  \"validTargets\": [\"a valid target\"],"
      + "  \"cacheSizeBytes\": 1000,\n"
      + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\",\n"
      + "  \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
      + "}";

  @Mock
  private ChannelLogger channelLogger;
  @Mock
  private SocketAddress rlsServerAddress;

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private Server inProcRlsServer;

  @Before
  public void setUp() throws Exception {
    doReturn(channelLogger).when(helper).getChannelLogger();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();

    inProcRlsServer = InProcessServerBuilder.forName(INPROC_RLS_SERVER)
        .addService(new InProcRlsServer(ImmutableList.of("addr1", "addr2", "addr3")))
        .build();
    grpcCleanupRule.register(inProcRlsServer.start());
    when(helper.createOobChannel(new EquivalentAddressGroup(rlsServerAddress), "service1"))
        .thenReturn(InProcessChannelBuilder.forName(INPROC_RLS_SERVER).build());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void name() throws IOException {
    String rlsLbConfig = "{"
        + "\"routeLookupConfig\": " + ROUTE_LOOKUP_CONFIG + ", "
        + "\"childPolicyConfigTargetFieldName\": \"foo.google.com\", "
        + "\"childPolicy\": [{\"grpclb\": {}}]"
        + "}";

    rlslb = provider.newLoadBalancer(helper);
    ConfigOrError policy = provider
        .parseLoadBalancingPolicyConfig((Map<String, ?>) JsonParser.parse(rlsLbConfig));
    assertThat(policy.getConfig()).isNotNull();
    rlslb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.of(new EquivalentAddressGroup(rlsServerAddress)))
        .setAttributes(Attributes.EMPTY)
        .setLoadBalancingPolicyConfig(policy.getConfig()).build());
  }

  @Test
  public void validateTestRlsServer() {
    ManagedChannel chan = helper
        .createOobChannel(new EquivalentAddressGroup(rlsServerAddress), "service1");
    RouteLookupServiceBlockingStub stub =
        RouteLookupServiceGrpc.newBlockingStub(chan);
    RlsProtoData.RouteLookupResponse resp = new RouteLookupResponseConverter()
        .convert(
            stub.routeLookup(
                new RouteLookupRequestConverter()
                    .reverse()
                    .convert(
                        new RlsProtoData.RouteLookupRequest("foo.google.com", "foo/bar", "grpc",
                            ImmutableMap.of("key1", "val1")))));
    assertThat(resp.getTarget()).isEqualTo("addr1");
    assertThat(resp.getHeaderData()).endsWith("0");

    resp = new RouteLookupResponseConverter()
        .convert(
            stub.routeLookup(
                new RouteLookupRequestConverter()
                    .reverse()
                    .convert(
                        new RlsProtoData.RouteLookupRequest("foo.google.com", "foo/bar", "grpc",
                            ImmutableMap.of("key1", "val1")))));
    assertThat(resp.getTarget()).isEqualTo("addr2");
    assertThat(resp.getHeaderData()).endsWith("1");
  }

  private final class InProcRlsServer extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private final List<String> addresses;

    public InProcRlsServer(List<String> addresses) {
      this.addresses = checkNotNull(addresses, "addresses");
      checkState(!addresses.isEmpty(), "should have at least one suffix");
    }

    @Override
    public void routeLookup(RouteLookupRequest request,
        StreamObserver<RouteLookupResponse> responseObserver) {
      RlsProtoData.RouteLookupRequest req =
          new RouteLookupRequestConverter().convert(request);
      try {
        RlsProtoData.RouteLookupResponse resp = doRouteLookup(req);
        responseObserver.onNext(new RouteLookupResponseConverter().reverse().convert(resp));
        responseObserver.onCompleted();
      } catch (Exception e) {
        responseObserver.onError(e);
      }
    }

    private AtomicInteger requestNum = new AtomicInteger();

    private RlsProtoData.RouteLookupResponse doRouteLookup(
        RlsProtoData.RouteLookupRequest request) {
      checkState(request.getTargetType().equals("grpc"), "can only handle grpc!");
      int numReq = requestNum.getAndIncrement();
      return
          new RlsProtoData.RouteLookupResponse(
              addresses.get(numReq % addresses.size()), "headerData-" + numReq);
    }
  }
}