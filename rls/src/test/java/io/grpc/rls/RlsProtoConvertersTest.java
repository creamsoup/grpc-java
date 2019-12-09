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
import io.grpc.lookup.v1alpha1.RouteLookupRequest;
import io.grpc.lookup.v1alpha1.RouteLookupResponse;
import io.grpc.lookup.v1alpha1.RouteLookupResponse.Header;
import io.grpc.rls.RlsProtoConverters.RouteLookupRequestConverter;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
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
}