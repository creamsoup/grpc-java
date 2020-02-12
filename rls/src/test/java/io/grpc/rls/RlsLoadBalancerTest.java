// /*
//  * Copyright 2020 The gRPC Authors
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  */
//
// package io.grpc.rls;
//
// import static com.google.common.base.Preconditions.checkNotNull;
// import static com.google.common.truth.Truth.assertThat;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.Mockito.CALLS_REAL_METHODS;
// import static org.mockito.Mockito.doReturn;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.when;
//
// import com.google.common.collect.ImmutableList;
// import com.google.common.collect.ImmutableMap;
// import io.grpc.Attributes;
// import io.grpc.CallOptions;
// import io.grpc.ChannelLogger;
// import io.grpc.ConnectivityState;
// import io.grpc.EquivalentAddressGroup;
// import io.grpc.LoadBalancer;
// import io.grpc.LoadBalancer.CreateSubchannelArgs;
// import io.grpc.LoadBalancer.Helper;
// import io.grpc.LoadBalancer.PickResult;
// import io.grpc.LoadBalancer.PickSubchannelArgs;
// import io.grpc.LoadBalancer.ResolvedAddresses;
// import io.grpc.LoadBalancer.Subchannel;
// import io.grpc.LoadBalancer.SubchannelPicker;
// import io.grpc.LoadBalancerProvider;
// import io.grpc.ManagedChannel;
// import io.grpc.Metadata;
// import io.grpc.MethodDescriptor;
// import io.grpc.NameResolver.ConfigOrError;
// import io.grpc.NameResolver.Factory;
// import io.grpc.Server;
// import io.grpc.SynchronizationContext;
// import io.grpc.inprocess.InProcessChannelBuilder;
// import io.grpc.inprocess.InProcessServerBuilder;
// import io.grpc.internal.FakeClock;
// import io.grpc.internal.JsonParser;
// import io.grpc.lookup.v1.RouteLookupServiceGrpc;
// import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceBlockingStub;
// import io.grpc.rls.RlsProtoConverters.RouteLookupRequestConverter;
// import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
// import io.grpc.stub.StreamObserver;
// import io.grpc.testing.GrpcCleanupRule;
// import io.grpc.testing.protobuf.SimpleRequest;
// import io.grpc.testing.protobuf.SimpleResponse;
// import io.grpc.testing.protobuf.SimpleServiceGrpc;
// import java.io.IOException;
// import java.net.SocketAddress;
// import java.util.Map;
// import javax.annotation.Nonnull;
// import org.junit.Before;
// import org.junit.Rule;
// import org.junit.Test;
// import org.junit.runner.RunWith;
// import org.junit.runners.JUnit4;
// import org.mockito.AdditionalAnswers;
// import org.mockito.Mock;
// import org.mockito.junit.MockitoJUnit;
// import org.mockito.junit.MockitoRule;
//
// @RunWith(JUnit4.class)
// public class RlsLoadBalancerTest {
//
//   private static final String INPROC_RLS_SERVER = "inproc-rls-server";
//   private static final String DEFAULT_TARGET = "us_east_1.cloudbigtable.googleapis.com";
//   private static final String ROUTE_LOOKUP_CONFIG = "{\n"
//       + "  \"grpcKeyBuilders\": [\n"
//       + "    {\n"
//       + "      \"names\": [\n"
//       + "        {\n"
//       + "          \"service\": \"service1\",\n"
//       + "          \"method\": \"create\"\n"
//       + "        }\n"
//       + "      ],\n"
//       + "      \"headers\": [\n"
//       + "        {\n"
//       + "          \"key\": \"user\","
//       + "          \"names\": [\"User\", \"Parent\"],\n"
//       + "          \"optional\": true\n"
//       + "        },\n"
//       + "        {\n"
//       + "          \"key\": \"id\","
//       + "          \"names\": [\"X-Google-Id\"],\n"
//       + "          \"optional\": true\n"
//       + "        }\n"
//       + "      ]\n"
//       + "    },\n"
//       + "    {\n"
//       + "      \"names\": [\n"
//       + "        {\n"
//       + "          \"service\": \"service1\",\n"
//       + "          \"method\": \"*\"\n"
//       + "        }\n"
//       + "      ],\n"
//       + "      \"headers\": [\n"
//       + "        {\n"
//       + "          \"key\": \"user\","
//       + "          \"names\": [\"User\", \"Parent\"],\n"
//       + "          \"optional\": true\n"
//       + "        },\n"
//       + "        {\n"
//       + "          \"key\": \"password\","
//       + "          \"names\": [\"Password\"],\n"
//       + "          \"optional\": true\n"
//       + "        }\n"
//       + "      ]\n"
//       + "    },\n"
//       + "    {\n"
//       + "      \"names\": [\n"
//       + "        {\n"
//       + "          \"service\": \"service3\",\n"
//       + "          \"method\": \"*\"\n"
//       + "        }\n"
//       + "      ],\n"
//       + "      \"headers\": ["
//       + "        {\n"
//       + "          \"key\": \"user\","
//       + "          \"names\": [\"User\", \"Parent\"],\n"
//       + "          \"optional\": true\n"
//       + "        }\n"
//       + "      ]\n"
//       + "    }\n"
//       + "  ],\n"
//       + "  \"lookupService\": \"service1\",\n"
//       + "  \"lookupServiceTimeout\": 2,\n"
//       + "  \"maxAge\": 300,\n"
//       + "  \"staleAge\": 240,\n"
//       + "  \"validTargets\": [\"a valid target\"],"
//       + "  \"cacheSizeBytes\": 1000,\n"
//       + "  \"defaultTarget\": \"" + DEFAULT_TARGET + "\",\n"
//       + "  \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
//       + "}";
//
//   @Rule
//   public final MockitoRule mocks = MockitoJUnit.rule();
//   @Rule
//   public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
//   @Mock
//   Subchannel defaultTargetSubchannel;
//
//   private final LoadBalancerProvider provider = new RlsLoadBalancerProvider();
//   private LoadBalancer.Helper helper =
//       mock(
//           Helper.class,
//           AdditionalAnswers.delegatesTo(new Helper() {
//             @Override
//             public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
//               return null;
//             }
//
//             @Override
//             public void updateBalancingState(@Nonnull ConnectivityState newState,
//                 @Nonnull SubchannelPicker newPicker) {
//
//             }
//
//             @Override
//             public Factory getNameResolverFactory() {
//               return null;
//             }
//
//             @Override
//             public String getAuthority() {
//               return null;
//             }
//           }));
//
//   @Mock
//   private ChannelLogger channelLogger;
//   @Mock
//   private SocketAddress rlsServerAddress;
//
//   private final SynchronizationContext syncContext = new SynchronizationContext(
//       new Thread.UncaughtExceptionHandler() {
//         @Override
//         public void uncaughtException(Thread t, Throwable e) {
//           throw new AssertionError(e);
//         }
//       });
//
//   private final FakeClock fakeClock = new FakeClock();
//   private Server inProcRlsServer;
//   private FakeScheduledExecutorServiceNooneShouldUse ses =
//       mock(FakeScheduledExecutorServiceNooneShouldUse.class, CALLS_REAL_METHODS);
//
//   @Before
//   public void setUp() throws Exception {
//     doReturn(channelLogger).when(helper).getChannelLogger();
//     doReturn(syncContext).when(helper).getSynchronizationContext();
//     doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
//
//     inProcRlsServer = InProcessServerBuilder.forName(INPROC_RLS_SERVER)
//         .addService(new TestRlsServer(ses))
//         .build();
//     grpcCleanupRule.register(inProcRlsServer.start());
//     when(helper.createOobChannel(new EquivalentAddressGroup(rlsServerAddress), "service1"))
//         .thenReturn(InProcessChannelBuilder.forName(INPROC_RLS_SERVER).build());
//
//     when(helper.createSubchannel(any(CreateSubchannelArgs.class)))
//         .thenReturn(mock(Subchannel.class));
//   }
//
//   @Test
//   @SuppressWarnings("unchecked")
//   public void name() throws IOException {
//     String rlsLbConfig = "{"
//         + "\"routeLookupConfig\": " + ROUTE_LOOKUP_CONFIG + ", "
//         + "\"childPolicyConfigTargetFieldName\": \"targetName\", "
//         + "\"childPolicy\": [{\"grpclb\": {}}]"
//         + "}";
//
//     RlsLoadBalancer rlslb = (RlsLoadBalancer) provider.newLoadBalancer(helper);
//     ConfigOrError policy =
//         provider.parseLoadBalancingPolicyConfig((Map<String, ?>) JsonParser.parse(rlsLbConfig));
//     assertThat(policy.getConfig()).isNotNull();
//     rlslb.handleResolvedAddresses(
//         ResolvedAddresses.newBuilder()
//             .setAddresses(ImmutableList.of(new EquivalentAddressGroup(rlsServerAddress)))
//             .setAttributes(Attributes.EMPTY)
//             .setLoadBalancingPolicyConfig(policy.getConfig()).build());
//     RlsPicker picker = rlslb.picker;
//
//     FakePickSubchannelArgs fakeSubchannelArgs =
//         new FakePickSubchannelArgs(
//             SimpleServiceGrpc.getUnaryRpcMethod(),
//             new Metadata(),
//             CallOptions.DEFAULT.withAuthority("foo.google.com"));
//
//     // first request is in pending, fall-back to default target
//     PickResult pickResult = picker.pickSubchannel(fakeSubchannelArgs);
//     // withNoResult
//     assertThat(pickResult.getStatus().isOk()).isTrue();
//     assertThat(pickResult.getSubchannel()).isNull();
//     assertThat(pickResult.isDrop()).isFalse();
//
//     // make the default target connected and verify
//
//     // make the cache hit
//
//     // make the cache staled
//
//     // make the cache expired
//
//     // make the staled back on
//
//     // make the cache staled
//
//     // make the cache expired
//
//     // make the staled backoff
//
//     // make the staled hit
//
//     // done.
//
//     // create another test for
//   }
//
//   final class FakePickSubchannelArgs extends PickSubchannelArgs {
//     private final CallOptions callOptions;
//     private final Metadata headers;
//     private final MethodDescriptor<?, ?> method;
//
//     FakePickSubchannelArgs(MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions) {
//       this.method = checkNotNull(method, "method");
//       this.headers = checkNotNull(headers, "headers");
//       this.callOptions = checkNotNull(callOptions, "callOptions");
//     }
//
//     @Override
//     public Metadata getHeaders() {
//       return headers;
//     }
//
//     @Override
//     public CallOptions getCallOptions() {
//       return callOptions;
//     }
//
//     @Override
//     public MethodDescriptor<?, ?> getMethodDescriptor() {
//       return method;
//     }
//   }
//
//   // ===============================================================================================
//   // below is helper classes e.g. test server
//
//   @Test
//   public void validateTestRlsServer() {
//     ManagedChannel chan = helper
//         .createOobChannel(new EquivalentAddressGroup(rlsServerAddress), "service1");
//     RouteLookupServiceBlockingStub stub =
//         RouteLookupServiceGrpc.newBlockingStub(chan);
//     RlsProtoData.RouteLookupResponse resp = new RouteLookupResponseConverter()
//         .convert(
//             stub.routeLookup(
//                 new RouteLookupRequestConverter()
//                     .reverse()
//                     .convert(
//                         new RlsProtoData.RouteLookupRequest("foo.google.com", "foo/bar", "grpc",
//                             ImmutableMap.of("key1", "val1")))));
//     assertThat(resp.getTarget()).isEqualTo("addr1");
//     assertThat(resp.getHeaderData()).endsWith("0");
//
//     resp = new RouteLookupResponseConverter()
//         .convert(
//             stub.routeLookup(
//                 new RouteLookupRequestConverter()
//                     .reverse()
//                     .convert(
//                         new RlsProtoData.RouteLookupRequest("foo.google.com", "foo/bar", "grpc",
//                             ImmutableMap.of("key1", "val1")))));
//     assertThat(resp.getTarget()).isEqualTo("addr2");
//     assertThat(resp.getHeaderData()).endsWith("1");
//   }
//
//   private final class Backend extends SimpleServiceGrpc.SimpleServiceImplBase {
//
//     private final String addr;
//
//     public Backend(String addr) {
//       this.addr = addr;
//     }
//
//     @Override
//     public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
//       responseObserver.onNext(
//           SimpleResponse.newBuilder()
//               .setResponseMessage(addr + "/" + request.getRequestMessage())
//               .build());
//       responseObserver.onCompleted();
//     }
//
//     @Override
//     public StreamObserver<SimpleRequest> clientStreamingRpc(
//         StreamObserver<SimpleResponse> responseObserver) {
//       throw new UnsupportedOperationException();
//     }
//
//     @Override
//     public void serverStreamingRpc(SimpleRequest request,
//         StreamObserver<SimpleResponse> responseObserver) {
//       throw new UnsupportedOperationException();
//     }
//
//     @Override
//     public StreamObserver<SimpleRequest> bidiStreamingRpc(
//         StreamObserver<SimpleResponse> responseObserver) {
//       throw new UnsupportedOperationException();
//     }
//   }
// }