package io.grpc.rls.integration;

import io.grpc.ManagedChannel;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.CacheRequest;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc.CachedRouteLookupServiceBlockingStub;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.util.Map;

public class Client {

  public static void main(String[] args) throws Exception {
    ManagedChannel clientChannel =
        NettyChannelBuilder
            // this will be ignored
            .forTarget("localhost")
            .defaultServiceConfig(getServiceConfig())
            .disableServiceConfigLookUp()
            .build();
    ManagedChannel rlsControlChannel = NettyChannelBuilder
        .forAddress("localhost", 8972)
        .build();
    CachedRouteLookupServiceBlockingStub rlsControlStub =
        CachedRouteLookupServiceGrpc.newBlockingStub(rlsControlChannel);

    RouteLookupRequest request1 = createRequest(path, server, keyMap);
    RouteLookupResponse response1 = createResponse(target, header);
    rlsControlStub.registerReturnValue(createCacheRequest(request1, response1));

    RouteLookupRequest request2 = createRequest(path, server, keyMap);
    RouteLookupResponse response2 = createResponse(target, header);
    rlsControlStub.registerReturnValue(createCacheRequest(request2, response2));



  }

  private static CacheRequest createCacheRequest(RouteLookupRequest request1,
      RouteLookupResponse response1) {
    return CacheRequest.newBuilder().setRequest(request1).setResponse(response1).build();
  }

  private static RouteLookupResponse createResponse(String target, String header) {
    return RouteLookupResponse.newBuilder().setTarget(target).setHeaderData(header).build();
  }

  private static RouteLookupRequest createRequest(String path, String server,
      Map<String, String> keyMap) {
    return RouteLookupRequest.newBuilder().setPath(path).setServer(server).putAllKeyMap(keyMap).build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getServiceConfig() throws IOException {
    String rlsConfigJson = "{\n"
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
