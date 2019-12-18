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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1alpha1.RouteLookupRequest;
import io.grpc.lookup.v1alpha1.RouteLookupResponse;
import io.grpc.lookup.v1alpha1.RouteLookupResponse.Header;
import io.grpc.rls.RlsProtoConverters.RouteLookupConfigConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupRequestConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RlsProtoConvertersTest {

  @Test
  public void convert_toRequestProto() {
    Converter<RouteLookupRequest, RlsProtoData.RouteLookupRequest> converter =
        new RouteLookupRequestConverter();
    RouteLookupRequest proto = RouteLookupRequest.newBuilder()
        .setServer("server")
        .setPath("path")
        .setTargetType("target")
        .putKeyMap("key1", "val1")
        .build();

    RlsProtoData.RouteLookupRequest object = converter.convert(proto);

    assertThat(object.getServer()).isEqualTo("server");
    assertThat(object.getPath()).isEqualTo("path");
    assertThat(object.getTargetType()).isEqualTo("target");
    assertThat(object.getKeyMap()).containsExactly("key1", "val1");
  }

  @Test
  public void convert_toRequestObject() {
    Converter<RlsProtoData.RouteLookupRequest, RouteLookupRequest> converter =
        new RouteLookupRequestConverter().reverse();
    RlsProtoData.RouteLookupRequest requestObject =
        new RlsProtoData.RouteLookupRequest(
            "server", "path", "target", ImmutableMap.of("key1", "val1"));

    RouteLookupRequest proto = converter.convert(requestObject);

    assertThat(proto.getServer()).isEqualTo("server");
    assertThat(proto.getPath()).isEqualTo("path");
    assertThat(proto.getTargetType()).isEqualTo("target");
    assertThat(proto.getKeyMapMap()).containsExactly("key1", "val1");
  }

  @Test
  public void convert_toResponseProto() {
    Converter<RouteLookupResponse, RlsProtoData.RouteLookupResponse> converter =
        new RouteLookupResponseConverter();
    RouteLookupResponse proto = RouteLookupResponse.newBuilder()
        .setTarget("target")
        .addHeaders(Header.newBuilder().setHeader("key1").setValue("val1").build())
        .build();

    RlsProtoData.RouteLookupResponse object = converter.convert(proto);

    assertThat(object.getTarget()).isEqualTo("target");
    assertThat(object.getHeaders()).containsExactly("key1", "val1");
  }

  @Test
  public void convert_toResponseObject() {
    Converter<RlsProtoData.RouteLookupResponse, RouteLookupResponse> converter =
        new RouteLookupResponseConverter().reverse();

    RlsProtoData.RouteLookupResponse object =
        new RlsProtoData.RouteLookupResponse("target", ImmutableList.of("key1", "val1"));

    RouteLookupResponse proto = converter.convert(object);

    assertThat(proto.getTarget()).isEqualTo("target");
    assertThat(proto.getHeadersList())
        .containsExactly(Header.newBuilder().setHeader("key1").setValue("val1").build());
  }

  @Test
  @Ignore("not implemented yet, impl if required.")
  public void convert_jsonRlsConfig() throws IOException {
    String jsonStr = "{\n"
        + "  \"grpcKeyBuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"create\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": {\n"
        + "        \"user\": {\n"
        + "          \"names\": [\n"
        + "            \"User\",\n"
        + "            \"Parent\"\n"
        + "          ],\n"
        + "          \"optional\": false\n"
        + "        },\n"
        + "        \"id\": {\n"
        + "          \"names\": [\n"
        + "            \"X-Google-Id\"\n"
        + "          ],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      }\n"
        + "    },\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service1\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": {\n"
        + "        \"user\": {\n"
        + "          \"names\": [\n"
        + "            \"User\",\n"
        + "            \"Parent\"\n"
        + "          ],\n"
        + "          \"optional\": false\n"
        + "        },\n"
        + "        \"password\": {\n"
        + "          \"names\": [\n"
        + "            \"Password\"\n"
        + "          ],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      }\n"
        + "    },\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"service3\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": {\n"
        + "        \"user\": {\n"
        + "          \"names\": [\n"
        + "            \"User\",\n"
        + "            \"Parent\"\n"
        + "          ],\n"
        + "          \"optional\": false\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"service1\",\n"
        + "  \"lookupServiceTimeout\": 2,\n"
        + "  \"maxAge\": 300,\n"
        + "  \"staleAge\": 240,\n"
        + "  \"cacheSize\": 1000,\n"
        + "  \"defaultTarget\": \"us_east_1.cloudbigtable.googleapis.com\",\n"
        + "  \"requestProcessingStrategy\": \"ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS\"\n"
        + "}";

    RouteLookupConfig expectedConfig =
        new RouteLookupConfig(
            ImmutableList.of(
                new GrpcKeyBuilder(
                    ImmutableList.of(new Name("service1", "create")),
                    ImmutableMap.of(
                        "user",
                        new NameMatcher(ImmutableList.of("User", "Parent")),
                        "id",
                        new NameMatcher(ImmutableList.of("X-Google-Id")))),
                new GrpcKeyBuilder(
                    ImmutableList.of(new Name("service1")),
                    ImmutableMap.of(
                        "user",
                        new NameMatcher(ImmutableList.of("User", "Parent")),
                        "password",
                        new NameMatcher(ImmutableList.of("Password")))),
                new GrpcKeyBuilder(
                    ImmutableList.of(new Name("service3")),
                    ImmutableMap.of(
                        "user",
                        new NameMatcher(ImmutableList.of("User", "Parent"))))),
            /* lookupService= */ "service1",
            /* lookupServiceTimeoutInMillis= */ TimeUnit.SECONDS.toMillis(2),
            /* maxAgeInMillis= */ TimeUnit.SECONDS.toMillis(300),
            /* staleAgeInMillis= */ TimeUnit.SECONDS.toMillis(240),
            /* cacheSize= */ 1000,
            /* defaultTarget= */ "us_east_1.cloudbigtable.googleapis.com",
            RequestProcessingStrategy.ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS);

    RouteLookupConfigConverter converter = new RouteLookupConfigConverter();
    Map<String, ?> parsedJson = (Map<String, ?>) JsonParser.parse(jsonStr);
    System.out.println("parsedJson: " + parsedJson);
    RouteLookupConfig converted = converter.convert(parsedJson);
    assertThat(converted).isEqualTo(expectedConfig);
  }
}