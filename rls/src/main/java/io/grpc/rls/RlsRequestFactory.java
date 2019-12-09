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
import com.google.common.collect.Table;
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
import javax.annotation.CheckReturnValue;

public final class RlsRequestFactory {

  // table of Path(serviceName.methodName or serviceName.*), rls request headerName, header fields
  private final Table<String, String, ReqHeaderNames> keyBuilderTable;

  public RlsRequestFactory(RouteLookupConfig rlsConfig) {
    this.keyBuilderTable = createKeyBuilderTable(rlsConfig);
  }

  private static Table<String, String, ReqHeaderNames> createKeyBuilderTable(
      RouteLookupConfig config) {
    Table<String, String, ReqHeaderNames> table = HashBasedTable.create();
    for (GrpcKeyBuilder grpcKeyBuilder : config.getGrpcKeybuilderList()) {
      for (Map.Entry<String, NameMatcher> entry : grpcKeyBuilder.getHeadersMap().entrySet()) {
        String rlsRequestHeaderName = entry.getKey();
        NameMatcher nameMatcher = entry.getValue();
        List<String> requestHeaders = nameMatcher.getNameList();
        for (Name name : grpcKeyBuilder.getNameList()) {
          String method = name.getMethod().isEmpty() ? "*" : name.getMethod();
          String path = name.getService() + "/" + method;
          table.put(
              path,
              rlsRequestHeaderName,
              new ReqHeaderNames(requestHeaders, nameMatcher.getOptionalMatch()));
        }
      }
    }
    return table;
  }

  @CheckReturnValue
  public RouteLookupRequest create(String url, Metadata metadata) throws URISyntaxException {
    URI uri = new URI(url);
    String path = uri.getPath();
    checkState(path != null && path.length() > 0, "Invalid path in the url: %s", url);
    // remove leading /
    path = path.substring(1);
    Map<String, ReqHeaderNames> keyBuilder = keyBuilderTable.row(path);
    // if no matching keyBuilder found, fall back to wildcard match (ServiceName/*)
    if (keyBuilder.isEmpty()) {
      String service = path.substring(0, path.lastIndexOf("/") + 1);
      keyBuilder = keyBuilderTable.row(service + "*");
    }
    Map<String, String> rlsRequestHeaders = createRequestHeaders(metadata, keyBuilder);
    return new RouteLookupRequest(uri.getHost(), path, "grpc", rlsRequestHeaders);
  }

  private Map<String, String> createRequestHeaders(
      Metadata metadata, Map<String, ReqHeaderNames> keyBuilder) {
    Map<String, String> rlsRequestHeaders = new HashMap<>();
    for (Map.Entry<String, ReqHeaderNames> entry : keyBuilder.entrySet()) {
      String value = null;
      for (String requestHeaderName : entry.getValue().requestHeaderNames) {
        value = metadata.get(Metadata.Key.of(requestHeaderName, Metadata.ASCII_STRING_MARSHALLER));
        if (value != null) {
          break;
        }
      }
      checkState(
          value != null || entry.getValue().optional,
          "Required header not found: ",
          entry.getKey());
      if (value != null) {
        rlsRequestHeaders.put(entry.getKey(), value);
      }
    }
    return rlsRequestHeaders;
  }

  private static final class ReqHeaderNames {
    List<String> requestHeaderNames;
    boolean optional;

    ReqHeaderNames(List<String> requestHeaderNames, boolean optional) {
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
}
