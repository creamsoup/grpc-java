package io.grpc.rls.integration;

import com.google.protobuf.Duration;
import io.grpc.ManagedChannel;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.BackendServiceGrpc;
import io.grpc.lookup.v1.CacheRequest;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc.CachedRouteLookupServiceBlockingStub;
import io.grpc.lookup.v1.EchoRequest;
import io.grpc.lookup.v1.EchoResponse;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Client {

  public static void main(String[] args) throws Exception {
    ManagedChannel clientChannel =
        NettyChannelBuilder
            // this will be ignored
            .forTarget("localhost")
            .defaultServiceConfig(getServiceConfig())
            .disableServiceConfigLookUp()
            .usePlaintext()
            .build();
    ManagedChannel rlsControlChannel = NettyChannelBuilder
        .forAddress("localhost", 8972)
        .usePlaintext()
        .build();
    CachedRouteLookupServiceBlockingStub rlsControlStub =
        CachedRouteLookupServiceGrpc.newBlockingStub(rlsControlChannel);

    System.out.println("register request1 response1");
    RouteLookupRequest request1 = createRequest("localhost", "rpc.lookup.v1.BackendService/Echo", Collections.<String, String>emptyMap());
    RouteLookupResponse response1 = createResponse("localhost:9001", "bar");
    rlsControlStub.registerReturnValue(createCacheRequest(request1, response1, 10));
    System.out.println("register request1 response1 done");

    System.out.println("register request2 response2");
    RouteLookupRequest request2 = createRequest("foo", "baz", Collections.<String, String>emptyMap());
    RouteLookupResponse response2 = createResponse("localhost:9002", "baz");
    rlsControlStub.registerReturnValue(createCacheRequest(request2, response2, 100));
    System.out.println("register request2 response2 done");

    EchoResponse resp1 =
        BackendServiceGrpc.newBlockingStub(clientChannel)
            .echo(EchoRequest.newBuilder().setMessage("message1").build());
    System.out.println("resp1: " + resp1);
  }

  private static CacheRequest createCacheRequest(RouteLookupRequest request1,
      RouteLookupResponse response1, long latencyInMillis) {
    return CacheRequest.newBuilder().setRequest(request1).setResponse(response1).setLatency(
        Duration.newBuilder().setNanos((int) TimeUnit.MILLISECONDS.toNanos(latencyInMillis)).build()).build();
  }

  private static RouteLookupResponse createResponse(String target, String header) {
    return RouteLookupResponse.newBuilder().setTarget(target).setHeaderData(header).build();
  }

  private static RouteLookupRequest createRequest(String server, String path,
      Map<String, String> keyMap) {
    return RouteLookupRequest.newBuilder().setPath(path).setServer(server).setTargetType("grpc").putAllKeyMap(keyMap).build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getServiceConfig() throws IOException {
    String rlsConfigJson = "{\n"
        + "  \"grpcKeyBuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"localhost\",\n"
        + "          \"method\": \"rpc.lookup.v1.BackendService/Echo\"\n"
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
        + "          \"service\": \"localhost\",\n"
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
        + "          \"service\": \"localhost2\",\n"
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
        + "  \"lookupService\": \"localhost:8972\",\n"
        + "  \"lookupServiceTimeout\": 2,\n"
        + "  \"maxAge\": 300,\n"
        + "  \"staleAge\": 240,\n"
        + "  \"validTargets\": [\"localhost:9001\", \"localhost:9002\"],"
        + "  \"cacheSizeBytes\": 1000,\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\",\n"
        + "  \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
        + "}";
    String grpclbJson = "{\"grpclb\": []}";
    String serviceConfig = "{"
        + "\"loadBalancingConfig\": [{"
        + "    \"rls\": {"
        + "      \"routeLookupConfig\": " + rlsConfigJson + ", "
        + "      \"childPolicy\": [" + grpclbJson + "],"
        + "      \"childPolicyConfigTargetFieldName\": \"targetName\""
        + "      }"
        + "  }]"
        + "}";
    return (Map<String, Object>) JsonParser.parse(serviceConfig);
  }
}
