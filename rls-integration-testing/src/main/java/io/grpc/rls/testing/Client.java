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

package io.grpc.rls.testing;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.NameResolverRegistry;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.BackendServiceGrpc;
import io.grpc.lookup.v1.BackendServiceGrpc.BackendServiceStub;
import io.grpc.lookup.v1.CacheRequest;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc.CachedRouteLookupServiceBlockingStub;
import io.grpc.lookup.v1.EchoRequest;
import io.grpc.lookup.v1.EchoResponse;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Integration test client. */
public final class Client {

  /** Main. */
  public static void main(String[] args) throws Exception {
    runClient();
  }

  static void runClient() throws Exception {
    // client will use localhost:12345 from RlsServer
    // fallback will be on 12346

    NameResolverRegistry.getDefaultRegistry().register(new FakeNameResolverProvider());
    ManagedChannel clientChannel =
        NettyChannelBuilder
            // this will be ignored
            .forTarget("fake-rls:///localhost")
            .defaultServiceConfig(getServiceConfig())
            .disableServiceConfigLookUp()
            .usePlaintext()
            .build();
    ManagedChannel rlsControlChannel = NettyChannelBuilder
        .forTarget("dns:///localhost:8972")
        .usePlaintext()
        .build();
    CachedRouteLookupServiceBlockingStub rlsControlStub =
        CachedRouteLookupServiceGrpc.newBlockingStub(rlsControlChannel);

    System.out.println("register request1 response1");
    RouteLookupRequest request1 =
        createRequest(
            "grpc.lookup.v1.BackendService",
            "Echo",
            Collections.<String, String>emptyMap());
    RouteLookupResponse response1 = createResponse("backend9001", "bar");
    rlsControlStub.registerReturnValue(createCacheRequest(request1, response1, 10));
    System.out.println("register request1 response1 done");

    System.out.println("register request2 response2");
    RouteLookupRequest request2 =
        createRequest(
            "grpc.lookup.v1.BackendService",
            "Echo",
            ImmutableMap.of("user", "creamsoup"));
    RouteLookupResponse response2 = createResponse("backend9002and3", "baz");
    rlsControlStub.registerReturnValue(createCacheRequest(request2, response2, 100));
    System.out.println("register request2 response2 done");

    System.out.println("register request3 response3");
    RouteLookupRequest request3 =
        createRequest(
            "grpc.lookup.v1.BackendService",
            "Echo",
            ImmutableMap.of("id", "ididid"));
    RouteLookupResponse response3 =
        createResponse("random", "foo should have been the first one but who cares");
    rlsControlStub.registerReturnValue(createCacheRequest(request3, response3, 20));

    System.out.println("register request2 response2 done");


    System.out.println("=========================================");

    BackendServiceStub bStub = BackendServiceGrpc.newStub(clientChannel);
    final int iter = 3;
    final CountDownLatch latch = new CountDownLatch(3 * iter);
    for (int i = 0; i < iter; i++) {
      System.out.println("iter " + i + " to backend1(9001): " + System.currentTimeMillis());
      final int iCopy = i;
      bStub.echo(EchoRequest.newBuilder().setMessage("message" + i).build(),
          new StreamObserver<EchoResponse>() {
            @Override
            public void onNext(EchoResponse value) {
              System.out.println("request " + iCopy + " to backend1(9001) -> " + value);
            }

            @Override
            public void onError(Throwable t) {
              System.out.println("error " + iCopy + " " + t.getMessage());
              t.printStackTrace(System.out);
              latch.countDown();
            }

            @Override
            public void onCompleted() {
              latch.countDown();
              System.out.println("done " + iCopy);
            }
          });

      System.out.println("iter " + i + " to backend2(9002): " + System.currentTimeMillis());
      Metadata metadata = new Metadata();
      metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "creamsoup");
      MetadataUtils.attachHeaders(bStub, metadata)
          .echo(EchoRequest.newBuilder().setMessage("message" + i).build(),
              new StreamObserver<EchoResponse>() {
                @Override
                public void onNext(EchoResponse value) {
                  System.out.println("request " + iCopy + " to backend1(9002) -> " + value);
                }

                @Override
                public void onError(Throwable t) {
                  System.out.println("error " + iCopy + " " + t.getMessage());
                  t.printStackTrace(System.out);
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  latch.countDown();
                  System.out.println("done " + iCopy);
                }
              });

      System.out.println("iter " + i + " to backend2(9002): " + System.currentTimeMillis());
      Metadata metadata2 = new Metadata();
      metadata2.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "ididid");
      MetadataUtils.attachHeaders(bStub, metadata2)
          .echo(EchoRequest.newBuilder().setMessage("message" + i).build(),
              new StreamObserver<EchoResponse>() {
                @Override
                public void onNext(EchoResponse value) {
                  System.out.println("request " + iCopy + " to backend2'(9002) -> " + value);
                }

                @Override
                public void onError(Throwable t) {
                  System.out.println("error " + iCopy + " " + t.getMessage());
                  t.printStackTrace(System.out);
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  latch.countDown();
                  System.out.println("done " + iCopy);
                }
              });
      Thread.sleep(100);
    }
    latch.await();
  }

  private static CacheRequest createCacheRequest(RouteLookupRequest request1,
      RouteLookupResponse response1, long latencyInMillis) {
    return
        CacheRequest.newBuilder()
            .setRequest(request1)
            .setResponse(response1)
            .setLatency(
                Duration.newBuilder()
                    .setNanos((int) TimeUnit.MILLISECONDS.toNanos(latencyInMillis))
                    .build())
            .build();
  }

  private static RouteLookupResponse createResponse(String target, String header) {
    return RouteLookupResponse.newBuilder().setTarget(target).setHeaderData(header).build();
  }

  private static RouteLookupRequest createRequest(String server, String path,
      Map<String, String> keyMap) {
    return
        RouteLookupRequest.newBuilder()
            .setPath(path)
            .setServer(server)
            .setTargetType("grpc")
            .putAllKeyMap(keyMap)
            .build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getServiceConfig() throws IOException {
    String rlsConfigJson = getRlsConfigJsonStr();
    String grpclbJson = "{\"grpclb\": {\"childPolicy\": [{\"pick_first\": {}}]}}";
    String serviceConfig = "{"
        + "\"loadBalancingConfig\": [{"
        + "    \"rls\": {"
        + "      \"routeLookupConfig\": " + rlsConfigJson + ", "
        + "      \"childPolicy\": [" + grpclbJson + "],"
        + "      \"childPolicyConfigTargetFieldName\": \"serviceName\""
        + "      }"
        + "  }]"
        + "}";
    return (Map<String, Object>) JsonParser.parse(serviceConfig);
  }

  private static String getRlsConfigJsonStr() {
    return "{\n"
          + "  \"grpcKeyBuilders\": [\n"
          + "    {\n"
          + "      \"names\": [\n"
          + "        {\n"
          + "          \"service\": \"grpc.lookup.v1.BackendService\",\n"
          + "          \"method\": \"Echo\"\n"
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
          + "          \"service\": \"grpc.lookup.v1.BackendService\",\n"
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
          + "    }\n"
          + "  ],\n"
          + "  \"lookupService\": \"localhost:8972\",\n"
          + "  \"lookupServiceTimeout\": 2,\n"
          + "  \"maxAge\": 300,\n"
          + "  \"staleAge\": 240,\n"
          + "  \"validTargets\": [\"localhost:9001\", \"localhost:9002\"],"
          + "  \"cacheSizeBytes\": 1000,\n"
          + "  \"defaultTarget\": \"defaultTarget\",\n"
          + "  \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
          + "}";
  }
}
