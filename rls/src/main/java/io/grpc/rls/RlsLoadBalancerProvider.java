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

package io.grpc.rls;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import java.io.IOException;
import java.util.Map;

public class RlsLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    // higher than grpclb=5
    return 6;
  }

  @Override
  public String getPolicyName() {
    return "rlslb";
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    //TODO also needs key builder map, request cache, lb policy config
    return new RlsLoadBalancer(helper, null);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingConfigPolicy) {
    /*
    try {
      return ConfigOrError.fromConfig(RlsLbPolicyConfiguration.from(rawLoadBalancingConfigPolicy));
    } catch (Exception e) {
      return ConfigOrError.fromError(
          Status.INVALID_ARGUMENT
              .withDescription("can't parse config: " + e.getMessage())
              .withCause(e));
    } */
    try {
      return ConfigOrError.fromConfig(JsonParser.parse(
          "{\n"
              + "  \"route_lookup_config\": {\n"
              + "    \"grpcKeyBuilders\": [\n"
              + "      {\n"
              + "        \"names\": [\n"
              + "          {\n"
              + "            \"service\": \"service1\",\n"
              + "            \"method\": \"create\"\n"
              + "          }\n"
              + "        ],\n"
              + "        \"headers\": {\n"
              + "          \"user\": {\n"
              + "            \"names\": [\n"
              + "              \"User\",\n"
              + "              \"Parent\"\n"
              + "            ],\n"
              + "            \"optional\": false\n"
              + "          },\n"
              + "          \"id\": {\n"
              + "            \"names\": [\n"
              + "              \"X-Google-Id\"\n"
              + "            ],\n"
              + "            \"optional\": true\n"
              + "          }\n"
              + "        }\n"
              + "      },\n"
              + "      {\n"
              + "        \"names\": [\n"
              + "          {\n"
              + "            \"service\": \"service1\",\n"
              + "            \"method\": \"*\"\n"
              + "          }\n"
              + "        ],\n"
              + "        \"headers\": {\n"
              + "          \"user\": {\n"
              + "            \"names\": [\n"
              + "              \"User\",\n"
              + "              \"Parent\"\n"
              + "            ],\n"
              + "            \"optional\": false\n"
              + "          },\n"
              + "          \"password\": {\n"
              + "            \"names\": [\n"
              + "              \"Password\"\n"
              + "            ],\n"
              + "            \"optional\": true\n"
              + "          }\n"
              + "        }\n"
              + "      },\n"
              + "      {\n"
              + "        \"names\": [\n"
              + "          {\n"
              + "            \"service\": \"service3\",\n"
              + "            \"method\": \"*\"\n"
              + "          }\n"
              + "        ],\n"
              + "        \"headers\": {\n"
              + "          \"user\": {\n"
              + "            \"names\": [\n"
              + "              \"User\",\n"
              + "              \"Parent\"\n"
              + "            ],\n"
              + "            \"optional\": false\n"
              + "          }\n"
              + "        }\n"
              + "      }\n"
              + "    ],\n"
              + "    \"lookupService\": \"service1\",\n"
              + "    \"lookupServiceTimeout\": 2,\n"
              + "    \"maxAge\": 300,\n"
              + "    \"staleAge\": 240,\n"
              + "    \"cacheSize\": 1000,\n"
              + "    \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\",\n"
              + "    \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
              + "  },\n"
              + "  \"child_policy\": [\n"
              + "    {\n"
              + "      \"grpclb\": {}\n"
              + "    }\n"
              + "  ],\n"
              + "  \"child_policy_config_target_field_name\": \"target\"\n"
              + "}"));
    } catch (IOException e) {
      throw new RuntimeException("Invalid fake service config", e);
    }
  }
}
