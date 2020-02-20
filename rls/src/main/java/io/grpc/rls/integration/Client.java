package io.grpc.rls.integration;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.NameResolverRegistry;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.BackendServiceGrpc;
import io.grpc.lookup.v1.BackendServiceGrpc.BackendServiceBlockingStub;
import io.grpc.lookup.v1.CacheRequest;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc.CachedRouteLookupServiceBlockingStub;
import io.grpc.lookup.v1.EchoRequest;
import io.grpc.lookup.v1.EchoResponse;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Client {

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
        createResponse("localhost:9002", "foo should have been the first one but who cares");
    rlsControlStub.registerReturnValue(createCacheRequest(request3, response3, 20));
    System.out.println("register request2 response2 done");


    System.out.println("=========================================");

    BackendServiceBlockingStub bStub = BackendServiceGrpc.newBlockingStub(clientChannel);
    for (int i = 0; i < 10; i++) {
      System.out.println("iter " + i + " to backend1(9001): " + System.currentTimeMillis());
      EchoResponse respBackend1 =
          bStub.echo(EchoRequest.newBuilder().setMessage("message" + i).build());
      System.out.println("request " + i + " to backend1(9001) -> " + respBackend1);

      System.out.println("iter " + i + " to backend2(9002): " + System.currentTimeMillis());
      Metadata metadata = new Metadata();
      metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "creamsoup");
      EchoResponse respBackend2 =
          MetadataUtils.attachHeaders(bStub, metadata)
              .echo(EchoRequest.newBuilder().setMessage("message" + i).build());
      System.out.println("request " + i + " to backend2(9002) -> " + respBackend2);

      System.out.println("iter " + i + " to backend2(9002): " + System.currentTimeMillis());
      Metadata metadata2 = new Metadata();
      metadata2.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "ididid");
      EchoResponse respBackend2prime =
          MetadataUtils.attachHeaders(bStub, metadata2)
              .echo(EchoRequest.newBuilder().setMessage("message" + i).build());
      System.out.println("request " + i + " to backend2'(9002) -> " + respBackend2prime);
    }
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
          + "  \"defaultTarget\": \"localhost:12346\",\n"
          + "  \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
          + "}";
  }
}
