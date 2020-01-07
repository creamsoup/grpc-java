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
import static com.google.common.base.Preconditions.checkState;
import static io.opencensus.internal.Utils.checkArgument;

import com.google.common.base.Converter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.lookup.v1alpha1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1alpha1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.Throttler.ThrottledException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

final class RouteLookupClientImpl
    extends AsyncRequestCache2<RouteLookupRequest, RouteLookupClientImpl.RouteLookupInfoImpl>
    implements RouteLookupClient {

  private static final Converter<RouteLookupRequest, io.grpc.lookup.v1alpha1.RouteLookupRequest>
      reqConverter = new RlsProtoConverters.RouteLookupRequestConverter().reverse();
  private static final Converter<RouteLookupResponse, io.grpc.lookup.v1alpha1.RouteLookupResponse>
      respConverter = new RouteLookupResponseConverter().reverse();

  private final Throttler throttler;
  private final ManagedChannel channel;
  private final RouteLookupServiceStub stub;

  private RouteLookupClientImpl(Builder builder) {
    super(
        builder.executor,
        builder.maxAgeMillis,
        builder.staleAgeMillis,
        builder.maxCacheSize,
        builder.callTimeoutMillis);
    this.throttler = builder.throttler;
    channel = builder.oobChannel;
    stub = RouteLookupServiceGrpc.newStub(channel);
  }

  @Override
  public RouteLookupInfo routeLookup(RouteLookupRequest request, Helper helper) {
    // return get(request);
    // TODO use above, this is not using cache for testing / debug purpose
    return rpcCall(request, helper);
  }

  @Override
  protected RouteLookupInfoImpl rpcCall(RouteLookupRequest request, Helper helper) {
    final SettableFuture<RouteLookupResponse> response = SettableFuture.create();
    if (throttler.shouldThrottle()) {
      return RouteLookupInfoImpl.createThrottled(request);
    }
    io.grpc.lookup.v1alpha1.RouteLookupRequest rlsRequest = reqConverter.convert(request);
    stub.routeLookup(rlsRequest, new StreamObserver<io.grpc.lookup.v1alpha1.RouteLookupResponse>() {
      @Override
      public void onNext(io.grpc.lookup.v1alpha1.RouteLookupResponse value) {
        response.set(respConverter.reverse().convert(value));
      }

      @Override
      public void onError(Throwable t) {
        throttler.registerBackendResponse(false);
      }

      @Override
      public void onCompleted() {
        throttler.registerBackendResponse(true);
      }
    });
    return RouteLookupInfoImpl.create(request, response);
  }

  @Override
  public void shutdown() {
    channel.shutdown();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private static final long MAX_ALLOWED_CACHE_SIZE = 10_000;

    private String target;
    private Throttler throttler = new HappyThrottler();
    private Executor executor = MoreExecutors.directExecutor();
    private long maxAgeMillis = TimeUnit.MINUTES.toMillis(5);
    private long staleAgeMillis = TimeUnit.MINUTES.toMillis(4);
    private long maxCacheSize = 128;
    private long callTimeoutMillis = 100L;
    private ManagedChannel oobChannel;

    public Builder setTarget(String target) {
      this.target = checkNotNull(target, "target");
      return this;
    }

    public Builder setThrottler(@Nullable Throttler throttler) {
      if (throttler == null) {
        this.throttler = new HappyThrottler();
      } else {
        this.throttler = throttler;
      }
      return this;
    }

    public Builder setExecutor(Executor executor) {
      this.executor = checkNotNull(executor, "executor");
      return this;
    }

    public Builder setMaxAgeMillis(long maxAgeMillis) {
      this.maxAgeMillis = maxAgeMillis;
      return this;
    }

    public Builder setStaleAgeMillis(long staleAgeMillis) {
      this.staleAgeMillis = staleAgeMillis;
      return this;
    }

    public Builder setMaxCacheSize(long maxCacheSize) {
      checkArgument(
          maxCacheSize <= MAX_ALLOWED_CACHE_SIZE,
          "maxCacheSize should be smaller or equals to %s",
          MAX_ALLOWED_CACHE_SIZE);
      this.maxCacheSize = maxCacheSize;
      return this;
    }

    public Builder setCallTimeoutMillis(long callTimeoutMillis) {
      this.callTimeoutMillis = callTimeoutMillis;
      return this;
    }

    public Builder setChannel(ManagedChannel oobChannel) {
      this.oobChannel = oobChannel;
      return this;
    }

    public RouteLookupClient build() {
      checkState(
          maxAgeMillis >= staleAgeMillis,
          "staleAge(%s) should be smaller than maxAge(%s)",
          staleAgeMillis,
          maxAgeMillis);
      checkNotNull(target, "target");
      return new RouteLookupClientImpl(this);
    }
  }

  /** A Throttler never throttles. */
  private static final class HappyThrottler implements Throttler {

    @Override
    public boolean shouldThrottle() {
      return false;
    }

    @Override
    public void registerBackendResponse(boolean throttled) {
      // no-op
    }
  }

  public static final class RouteLookupInfoImpl implements RouteLookupInfo {

    private final ListenableFuture<RouteLookupResponse> routeLookupResponse;
    private final ChildPolicyWrapper childPolicyWrapper;

    public RouteLookupInfoImpl(
        ListenableFuture<RouteLookupResponse> routeLookupResponse,
        ChildPolicyWrapper childPolicyWrapper) {
      this.routeLookupResponse = routeLookupResponse;
      this.childPolicyWrapper = childPolicyWrapper;
    }

    public static RouteLookupInfoImpl create(
        RouteLookupRequest request,
        ListenableFuture<RouteLookupResponse> response) {
      ChildPolicyWrapper wrapper = new ChildPolicyWrapper(request.getServer());
      return new RouteLookupInfoImpl(response, wrapper);
    }

    public static RouteLookupInfoImpl createThrottled(RouteLookupRequest request) {
      SettableFuture<RouteLookupResponse> throttledFuture = SettableFuture.create();
      ThrottledException throttledException = new ThrottledException();
      throttledFuture.setException(throttledException);
      ChildPolicyWrapper wrapper = new ChildPolicyWrapper(request.getServer());
      return new RouteLookupInfoImpl(throttledFuture, wrapper);
    }

    @Override
    public ChildPolicyWrapper getChildPolicyWrapper() {
      return childPolicyWrapper;
    }

    @Override
    public void addListener(Runnable listener, Executor executor) {
      routeLookupResponse.addListener(listener, executor);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return routeLookupResponse.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
      return routeLookupResponse.isCancelled();
    }

    @Override
    public boolean isDone() {
      return routeLookupResponse.isDone();
    }

    @Override
    public RouteLookupResponse get() throws InterruptedException, ExecutionException {
      return routeLookupResponse.get();
    }

    @Override
    public RouteLookupResponse get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return routeLookupResponse.get(timeout, unit);
    }
  }
}
