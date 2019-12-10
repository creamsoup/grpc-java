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

  // TODO hide constructors and use builder / factory method

  @Immutable
  static final class RouteLookupRequest {
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
  static final class RouteLookupResponse {
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

  @Immutable
  public static final class RouteLookupConfig {

    /**
     * Unordered specifications for constructing keys for gRPC requests.  All GrpcKeyBuilders on
     * this list must have unique "name" fields so that the client is free to prebuild a hash map
     * keyed by name.  If no GrpcKeyBuilder matches, an empty map will be sent to the lookup
     * service; it should likely reply with a global default route and raise an alert.
     */
    private final ImmutableList<GrpcKeyBuilder> grpcKeyBuilders;


    /**
     * The name of the lookup service as a gRPC URI.  Typically, this will be a subdomain of the
     * target, such as "lookup.datastore.googleapis.com".
     */
    private final String lookupService;

    /** Configure a timeout value for lookup service requests. Required. */
    private final long lookupServiceTimeoutInMillis;

    /**
     * How long are responses valid for (like HTTP Cache-Control). If omitted (i.e. 0), responses
     * are considered not to be cacheable. This value is clamped to 5 minutes to avoid unflushable
     * bad responses.
     */
    private final long maxAgeInMillis;

    /**
     * After a response has been in the client cache for this amount of time and is re-requested,
     * start an asynchronous RPC to re-validate it. This value should be less than max_age by at
     * least the length of a typical RTT to the Route Lookup Service to fully mask the RTT latency.
     * If omitted, keys are only re-requested after they have expired.
     */
    private final long staleAgeInMillis;

    /**
     * Rough indicator of amount of memory to use for the client cache, in bytes.  Some of the data
     * structure overhead is not accounted for, so actual memory consumed will be somewhat greater
     * than this value. If this field is omitted or set to zero, a client default will be used. The
     * value may be capped to a lower amount based on client configuration.
     */
    private final long cacheSize;

    /**
     * This value provides a default target to use if needed.  It should not be defined if request
     * processing strategy is SYNC_LOOKUP_CLIENT_SEES_ERROR. Note that requests can be routed only
     * to a subdomain of the original target, e.g. "us_east_1.cloudbigtable.googleapis.com".
     */
    private final String defaultTarget;

    /** Specify how to process a request when a mapping is not available. */
    private final RequestProcessingStrategy requestProcessingStrategy;

    public RouteLookupConfig(
        List<GrpcKeyBuilder> grpcKeyBuilders,
        String lookupService,
        long lookupServiceTimeoutInMillis,
        long maxAgeInMillis,
        long staleAgeInMillis,
        long cacheSize,
        String defaultTarget,
        RequestProcessingStrategy requestProcessingStrategy) {
      this.grpcKeyBuilders = ImmutableList.copyOf(checkNotNull(grpcKeyBuilders, "grpcKeyBuilders"));
      this.lookupService = lookupService;
      this.lookupServiceTimeoutInMillis = lookupServiceTimeoutInMillis;
      this.maxAgeInMillis = maxAgeInMillis;
      this.staleAgeInMillis = staleAgeInMillis;
      this.cacheSize = cacheSize;
      this.defaultTarget = defaultTarget;
      this.requestProcessingStrategy = requestProcessingStrategy;
    }

    public ImmutableList<GrpcKeyBuilder> getGrpcKeyBuilders() {
      return grpcKeyBuilders;
    }

    public String getLookupService() {
      return lookupService;
    }

    public long getLookupServiceTimeoutInMillis() {
      return lookupServiceTimeoutInMillis;
    }

    public long getMaxAgeInMillis() {
      return maxAgeInMillis;
    }

    public long getStaleAgeInMillis() {
      return staleAgeInMillis;
    }

    public long getCacheSize() {
      return cacheSize;
    }

    public String getDefaultTarget() {
      return defaultTarget;
    }

    public RequestProcessingStrategy getRequestProcessingStrategy() {
      return requestProcessingStrategy;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteLookupConfig that = (RouteLookupConfig) o;
      return lookupServiceTimeoutInMillis == that.lookupServiceTimeoutInMillis
          && maxAgeInMillis == that.maxAgeInMillis
          && staleAgeInMillis == that.staleAgeInMillis
          && cacheSize == that.cacheSize
          && Objects.equal(grpcKeyBuilders, that.grpcKeyBuilders)
          && Objects.equal(lookupService, that.lookupService)
          && Objects.equal(defaultTarget, that.defaultTarget)
          && requestProcessingStrategy == that.requestProcessingStrategy;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          grpcKeyBuilders,
          lookupService,
          lookupServiceTimeoutInMillis,
          maxAgeInMillis,
          staleAgeInMillis,
          cacheSize,
          defaultTarget,
          requestProcessingStrategy);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("grpcKeyBuilders", grpcKeyBuilders)
          .add("lookupService", lookupService)
          .add("lookupServiceTimeoutInMillis", lookupServiceTimeoutInMillis)
          .add("maxAgeInMillis", maxAgeInMillis)
          .add("staleAgeInMillis", staleAgeInMillis)
          .add("cacheSize", cacheSize)
          .add("defaultTarget", defaultTarget)
          .add("requestProcessingStrategy", requestProcessingStrategy)
          .toString();
    }
  }

  enum RequestProcessingStrategy {
    /**
     * Query the RLS and process the request using target returned by the lookup. The target will
     * then be cached and used for processing subsequent requests for the same key. Any errors
     * during lookup service processing will fall back to default target for request processing.
     */
    SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR,

    /**
     * Query the RLS and process the request using target returned by the lookup. The target will
     * then be cached and used for processing subsequent requests for the same key. Any errors
     * during lookup service processing will return an error back to the client.  Services with
     * strict regional routing requirements should use this strategy.
     */
    SYNC_LOOKUP_CLIENT_SEES_ERROR,

    /**
     * Query the RLS asynchronously but respond with the default target.  The target in the lookup
     * response will then be cached and used for subsequent requests.  Services with strict latency
     * requirements (but not strict regional routing requirements) should use this strategy.
     */
    ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS;
  }

  @Immutable
  static final class NameMatcher {

    /** Ordered list of names; the first non-empty value will be used. */
    private final ImmutableList<String> names;

    /**
     * If true, make this extraction optional. A key builder will still match if no value is found.
     */
    private final boolean optional;

    NameMatcher(List<String> names) {
      this(names, false);
    }

    NameMatcher(List<String> names, boolean optional) {
      this.names = ImmutableList.copyOf(checkNotNull(names, "names"));
      this.optional = optional;
    }

    public ImmutableList<String> names() {
      return names;
    }

    public boolean isOptional() {
      return optional;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      NameMatcher that = (NameMatcher) o;
      return optional == that.optional
          && Objects.equal(names, that.names);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(names, optional);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("names", names)
          .add("optional", optional)
          .toString();
    }
  }

  static final class GrpcKeyBuilder {
    /**
     * To match, one of the given Name fields must match; the service and method fields are
     * specified as fixed strings.  The service name is required and includes the proto package
     * name.  The method name may be omitted, in which case any method on the given service is
     * matched.
     */
    private final ImmutableList<Name> names;

    /** All listed headers must match (or be optional). */
    private final ImmutableMap<String, NameMatcher> headers;

    public GrpcKeyBuilder(List<Name> names, Map<String, NameMatcher> headers) {
      this.names = ImmutableList.copyOf(checkNotNull(names, "names"));
      this.headers = ImmutableMap.copyOf(checkNotNull(headers, "headers"));
    }

    public ImmutableList<Name> getNames() {
      return names;
    }

    public ImmutableMap<String, NameMatcher> getHeaders() {
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
      GrpcKeyBuilder that = (GrpcKeyBuilder) o;
      return Objects.equal(names, that.names) && Objects
          .equal(headers, that.headers);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(names, headers);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("names", names)
          .add("headers", headers)
          .toString();
    }
  }

  static final class Name {
    private final String service;
    private final String method;

    public Name(String service) {
      this(service, "*");
    }

    public Name(String service, String method) {
      this.service = checkNotNull(service, "service");
      this.method = method;
    }

    public String getService() {
      return service;
    }

    public String getMethod() {
      return method;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Name name = (Name) o;
      return Objects.equal(service, name.service)
          && Objects.equal(method, name.method);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(service, method);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("service", service)
          .add("method", method)
          .toString();
    }
  }
}
