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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.protobuf.Duration;
import io.grpc.Metadata;
import io.grpc.lookup.v1alpha1.GrpcKeyBuilder;
import io.grpc.lookup.v1alpha1.GrpcKeyBuilder.Name;
import io.grpc.lookup.v1alpha1.NameMatcher;
import io.grpc.lookup.v1alpha1.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyBuilderMap {
  static RouteLookupConfig config = RouteLookupConfig.newBuilder()
      .setLookupService("service1")
      .setMaxAge(Duration.newBuilder().setSeconds(300).build())
      .setStaleAge(Duration.newBuilder().setSeconds(240).build())
      .setCacheSize(1000)
      .setDefaultTarget("us_east_1.cloudbigtable.googleapis.com")
      .addGrpcKeybuilder(
          GrpcKeyBuilder.newBuilder()
              .addName(Name.newBuilder().setService("service1").setMethod("create").build())
              .putHeaders("user", NameMatcher.newBuilder().addName("User").addName("Parent").build())
              .putHeaders("id", NameMatcher.newBuilder().addName("X-Google-Id").setOptionalMatch(true).build())
              .build())
      .addGrpcKeybuilder(
          GrpcKeyBuilder.newBuilder().addName(Name.newBuilder().setService("service1").setMethod("delete").build()).build())
      .addGrpcKeybuilder(
          GrpcKeyBuilder.newBuilder().addName(Name.newBuilder().setService("service3").build()).build())
      .build();

  // map of Path(serviceName.methodName or serviceName.*), rls request headerName, header fields (this field can be optional)
  static Table<String, String, ReqHeaderNames> keyBuilderTable;

  public static final class ReqHeaderNames {
    List<String> requestHeaderNames;
    boolean optional;

    public ReqHeaderNames(List<String> requestHeaderNames, boolean optional) {
      this.requestHeaderNames = requestHeaderNames;
      this.optional = optional;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("requestHeaderNames", requestHeaderNames)
          .add("optional", optional)
          .toString();
    }
  }


  public static void main(String[] args) throws URISyntaxException {
    // sample request
    String url = "http://foo.com/service1/create";
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("User", Metadata.ASCII_STRING_MARSHALLER), "test");
    metadata.put(Metadata.Key.of("X-Google-Id", Metadata.ASCII_STRING_MARSHALLER), "123");
    metadata.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");

    // create key buildermap
    keyBuilderTable = createKeyBuilderMap(config);
    System.out.println("table:\n" + keyBuilderTable);

    RouteLookupRequest request = create(url, metadata);
    System.out.println("Request is created: " + request);
  }

  private static Table<String, String, ReqHeaderNames> createKeyBuilderMap(
      RouteLookupConfig config) {
    Table<String, String, ReqHeaderNames> table = HashBasedTable.create();
    for (GrpcKeyBuilder grpcKeyBuilder : config.getGrpcKeybuilderList()) {
      for (Map.Entry<String, NameMatcher> entry : grpcKeyBuilder.getHeadersMap().entrySet()) {
        String rlsRequestHeaderName = entry.getKey();
        NameMatcher nameMatcher = entry.getValue();
        List<String> requestHeaders = nameMatcher.getNameList();
        for (Name name : grpcKeyBuilder.getNameList()) {
          String path = name.getService() + "/" + name.getMethod();
          table.put(
              path,
              rlsRequestHeaderName,
              new ReqHeaderNames(requestHeaders, nameMatcher.getOptionalMatch()));
        }
      }
    }
    return table;
  }

  private static RouteLookupRequest create(String url, Metadata metadata)
      throws URISyntaxException {
    URI uri = new URI(url);
    String path = uri.getPath().substring(1);
    System.out.println("looking for keyBuilder for " + path);
    Map<String, ReqHeaderNames> keyBuilder = keyBuilderTable.row(path);
    System.out.println("keyBuilder for url: " + url + " is " + keyBuilder);
    //TODO add fallback wildcard logic
    Map<String, String> rlsRequestHeaders = new HashMap<>();
    for (Map.Entry<String, ReqHeaderNames> entry : keyBuilder.entrySet()) {
      String val = null;
      for (String requestHeaderName : entry.getValue().requestHeaderNames) {
        val = metadata.get(Metadata.Key.of(requestHeaderName, Metadata.ASCII_STRING_MARSHALLER));
        if (val != null) {
          break;
        }
      }
      checkState(val != null || entry.getValue().optional, "Required header not found: ", entry.getKey());
      if (val != null) {
        rlsRequestHeaders.put(entry.getKey(), val);
      }
    }

    return new RouteLookupRequest(uri.getHost(), path, "grpc", rlsRequestHeaders);
  }
}
