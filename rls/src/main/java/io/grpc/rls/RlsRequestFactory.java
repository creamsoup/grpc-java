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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import io.grpc.Metadata;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckReturnValue;

/**
 * A RlsRequestFactory creates {@link RouteLookupRequest} using key builder map from {@link
 * RouteLookupConfig}.
 */
public final class RlsRequestFactory {

  // table of Path(serviceName.methodName or serviceName.*), rls request headerName, header fields
  private final Table<String, String, NameMatcher> keyBuilderTable;

  public RlsRequestFactory(RouteLookupConfig rlsConfig) {
    this.keyBuilderTable = createKeyBuilderTable(rlsConfig);
  }

  private static Table<String, String, NameMatcher> createKeyBuilderTable(
      RouteLookupConfig config) {
    Table<String, String, NameMatcher> table = HashBasedTable.create();
    for (GrpcKeyBuilder grpcKeyBuilder : config.getGrpcKeyBuilders()) {
      for (NameMatcher nameMatcher : grpcKeyBuilder.getHeaders()) {
        List<String> requestHeaders = nameMatcher.names();
        for (Name name : grpcKeyBuilder.getNames()) {
          String method = name.getMethod().isEmpty() ? "*" : name.getMethod();
          String path = name.getService() + "/" + method;
          table.put(
              path,
              nameMatcher.getKey(),
              new NameMatcher(nameMatcher.getKey(), requestHeaders, /* optional= */ true));
        }
      }
    }
    return table;
  }

  @CheckReturnValue
  public RouteLookupRequest create(String service, String method, Metadata metadata) {
    checkNotNull(service, "service");
    checkNotNull(method, "method");
    // removing leading '/'
    if (method.charAt(0) == '/') {
      method = method.substring(1);
    }
    Map<String, NameMatcher> keyBuilder = keyBuilderTable.row(service + "/" + method);
    // if no matching keyBuilder found, fall back to wildcard match (ServiceName/*)
    if (keyBuilder.isEmpty()) {
      keyBuilder = keyBuilderTable.row(service + "/*");
    }
    Map<String, String> rlsRequestHeaders = createRequestHeaders(metadata, keyBuilder);
    return new RouteLookupRequest(service, method, "grpc", rlsRequestHeaders);
  }

  private Map<String, String> createRequestHeaders(
      Metadata metadata, Map<String, NameMatcher> keyBuilder) {
    Map<String, String> rlsRequestHeaders = new HashMap<>();
    for (Map.Entry<String, NameMatcher> entry : keyBuilder.entrySet()) {
      String value = null;
      for (String requestHeaderName : entry.getValue().names()) {
        value = metadata.get(Metadata.Key.of(requestHeaderName, Metadata.ASCII_STRING_MARSHALLER));
        if (value != null) {
          break;
        }
      }
      if (value != null) {
        rlsRequestHeaders.put(entry.getKey(), value);
      }
    }
    return rlsRequestHeaders;
  }
}
