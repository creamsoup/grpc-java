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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import javax.annotation.concurrent.Immutable;

/** RlsProtoData is a collection of internal representation of RouteLookupService proto messages. */
public final class RlsProtoData {

  @Immutable
  public static final class RouteLookupRequest {
    /**
     * Full host name of the target server, e.g. firestore.googleapis.com. Only set for gRPC
     * requests; HTTP requests must use key_map explicitly.
     */
    private final String server;

    /**
     * Full path of the request, e.g. service and method for gRPC; does not include any query
     * component. Only set for gRPC requests; HTTP requests must use key_map explicitly.
     */
    private final String path;

    /**
     * Target type allows the client to specify what kind of target format it would like from RLS to
     * allow it to find the regional server, e.g. "grpc".
     */
    private final String targetType;

    /**
     * Map of key values extracted via KeyBuilder for URL or gRPC request.
     */
    private final ImmutableMap<String, String> keyMap;

    public RouteLookupRequest(
        String server, String path, String targetType, Map<String, String> keyMap) {
      this.server = checkNotNull(server, "server");
      this.path = checkNotNull(path, "path");
      this.targetType = checkNotNull(targetType, "targetName");
      this.keyMap = ImmutableMap.copyOf(checkNotNull(keyMap, "keyMap"));
    }

    public String getServer() {
      return server;
    }

    public String getPath() {
      return path;
    }

    public String getTargetType() {
      return targetType;
    }

    public ImmutableMap<String, String> getKeyMap() {
      return keyMap;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteLookupRequest that = (RouteLookupRequest) o;
      return Objects.equal(server, that.server)
          && Objects.equal(path, that.path)
          && Objects.equal(targetType, that.targetType)
          && Objects.equal(keyMap, that.keyMap);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(server, path, targetType, keyMap);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("server", server)
          .add("path", path)
          .add("targetName", targetType)
          .add("keyMap", keyMap)
          .toString();
    }
  }

  @Immutable
  public static final class RouteLookupResponse {
    /**
     * Actual addressable entity to use for routing decision, using syntax requested by the request
     * target_type.
     */
    String target;

    /**
     * Optional HTTP headers or gRPC metadata to add to the client request. Cached with "target" and
     * sent with all requests that match the request key.Allows the RLS to pass its work product to
     * the final regional AFE.
     */
    ImmutableList<String> headers;

    public RouteLookupResponse(String target, List<String> headers) {
      this.target = checkNotNull(target, "target");
      checkNotNull(headers, "headers");
      checkArgument(headers.size() % 2 == 0, "Invalid header size: %s", headers.size());
      this.headers = ImmutableList.copyOf(headers);
    }

    public String getTarget() {
      return target;
    }

    public ImmutableList<String> getHeaders() {
      return headers;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteLookupResponse that = (RouteLookupResponse) o;
      return Objects.equal(target, that.target) && Objects.equal(headers, that.headers);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(target, headers);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("target", target)
          .add("headers", headers)
          .toString();
    }
  }
}
