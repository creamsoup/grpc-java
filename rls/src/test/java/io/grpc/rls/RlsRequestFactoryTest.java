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
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import io.grpc.Metadata;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RlsRequestFactoryTest {
  private final RouteLookupConfig config =
      new RouteLookupConfig(
          ImmutableList.of(
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("service1", "create")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                      new NameMatcher("id", ImmutableList.of("X-Google-Id"), true))),
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("service1")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                      new NameMatcher("password", ImmutableList.of("Password"), true))),
              new GrpcKeyBuilder(
                  ImmutableList.of(new Name("service3")),
                  ImmutableList.of(
                      new NameMatcher("user", ImmutableList.of("User", "Parent"), true)))),
          /* lookupService= */ "service1",
          /* lookupServiceTimeoutInMillis= */ TimeUnit.SECONDS.toMillis(2),
          /* maxAgeInMillis= */ TimeUnit.SECONDS.toMillis(300),
          /* staleAgeInMillis= */ TimeUnit.SECONDS.toMillis(240),
          /* cacheSize= */ 1000,
          /* validTargets= */ ImmutableList.of("a valid target"),
          /* defaultTarget= */ "us_east_1.cloudbigtable.googleapis.com",
          RequestProcessingStrategy.ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS);

  private final RlsRequestFactory factory = new RlsRequestFactory(config);

  @Test
  public void create_pathMatches() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("foo.com", "/service1/create", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("service1/create");
    assertThat(request.getServer()).isEqualTo("foo.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test", "id", "123");
  }

  @Test
  @Ignore("grpcKeyBuilder is always optional")
  public void create_missingRequiredHeader() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    try {
      RouteLookupRequest unused = factory.create("foo.com", "/service1/create", metadata);
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().startsWith("Required header not found:");
      assertThat(e).hasMessageThat().contains("user");
    }
  }

  @Test
  public void create_pathFallbackMatches() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("Parent", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("Password", Metadata.ASCII_STRING_MARSHALLER), "hunter2");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("foo.com", "/service1/update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("service1/update");
    assertThat(request.getServer()).isEqualTo("foo.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test", "password", "hunter2");
  }

  @Test
  public void create_pathFallbackMatches_optionalHeaderMissing() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("foo.com", "/service1/update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("service1/update");
    assertThat(request.getServer()).isEqualTo("foo.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test");
  }

  @Test
  public void create_unknownPath() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("foo.com", "/service999/update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("service999/update");
    assertThat(request.getServer()).isEqualTo("foo.com");
    assertThat(request.getKeyMap()).isEmpty();
  }

  @Test
  public void create_noMethodInRlsConfig() {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    RouteLookupRequest request = factory.create("foo.com", "/service3/update", metadata);

    assertThat(request.getTargetType()).isEqualTo("grpc");
    assertThat(request.getPath()).isEqualTo("service3/update");
    assertThat(request.getServer()).isEqualTo("foo.com");
    assertThat(request.getKeyMap()).containsExactly("user", "test");
  }
}