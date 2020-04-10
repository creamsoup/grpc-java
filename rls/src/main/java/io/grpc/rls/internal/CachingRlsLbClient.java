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

package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Converter;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.internal.TimeProvider;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.internal.ChildPolicyReportingHelper.ChildLbStatusListener;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.internal.LruCache.EvictionListener;
import io.grpc.rls.internal.LruCache.EvictionType;
import io.grpc.rls.internal.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.internal.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.internal.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.internal.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.internal.Throttler.ThrottledException;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A CachingRlsLbClient is a cache for rls service. All the methods in this class are non blocking.
 * This async behavior is reflected to the {@link #get(RouteLookupRequest)} method, when the cache
 * is requested but not fully populated, it returns uncompleted {@link ListenableFuture} which
 * allows the users to wait or ignore until the computation is completed.
 *
 * <p>On top of regular cache behavior, it supports max age and stale state of cache value. The key
 * will be expired when max age is reached. When the cached entry is staled (age of cache is in
 * between staled and max), AsyncRequestCache will asynchronously refresh the entry.
 */
// TODO(creamsoup) revisit javadoc
@ThreadSafe
public final class CachingRlsLbClient {

  private static final Converter<RouteLookupRequest, io.grpc.lookup.v1.RouteLookupRequest>
      reqConverter = new RlsProtoConverters.RouteLookupRequestConverter().reverse();
  private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
      respConverter = new RouteLookupResponseConverter().reverse();

  // All cache status changes (pending, backoff, success) must be under this lock
  private final Object lock = new Object();
  // LRU cache based on access order (BACKOFF and actual data will be here)
  @GuardedBy("lock")
  private final LinkedHashLruCache<RouteLookupRequest, CacheEntry> linkedHashLruCache;
  // any RPC on the fly will cached in this map
  @GuardedBy("lock")
  private final Map<RouteLookupRequest, PendingCacheEntry> pendingCallCache = new HashMap<>();

  private final SynchronizationContext synchronizationContext;
  private final ScheduledExecutorService scheduledExecutorService;
  private final TimeProvider timeProvider;
  private final Throttler throttler;

  private final LbPolicyConfiguration lbPolicyConfig;
  private final BackoffPolicy.Provider backoffProvider;
  private final long maxAgeNanos;
  private final long staleAgeNanos;
  private final long callTimeoutNanos;

  private final Helper helper;
  private final ManagedChannel rlsChannel;
  private final RouteLookupServiceStub rlsStub;
  private final RlsPicker rlsPicker;
  private final ChildLbResolvedAddressFactory childLbResolvedAddressFactory;
  private final ChildLoadBalancerHelperProvider childLbHelperProvider;
  @Nullable
  private final ChildLbStatusListener childLbStatusListener;
  private final SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();

  CachingRlsLbClient(Builder builder) {
    helper = checkNotNull(builder.helper, "helper");
    scheduledExecutorService = helper.getScheduledExecutorService();
    synchronizationContext = helper.getSynchronizationContext();
    RouteLookupConfig rlsConfig = checkNotNull(builder.rlsConfig, "rlsConfig");
    checkState(rlsConfig.getMaxAgeInMillis() > 0L, "maxAgeMillis should be positive");
    checkState(rlsConfig.getStaleAgeInMillis() > 0L, "staleAgeMillis should be positive");
    checkState(
        rlsConfig.getMaxAgeInMillis() >= rlsConfig.getStaleAgeInMillis(),
        "maxAgeMillis should be greater than equals to staleAgeMillis");
    checkState(
        rlsConfig.getLookupServiceTimeoutInMillis() > 0L,
        "getLookupServiceTimeoutInMillis should be positive");
    maxAgeNanos = TimeUnit.MILLISECONDS.toNanos(rlsConfig.getMaxAgeInMillis());
    staleAgeNanos = TimeUnit.MILLISECONDS.toNanos(rlsConfig.getStaleAgeInMillis());
    callTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(rlsConfig.getLookupServiceTimeoutInMillis());
    timeProvider = checkNotNull(builder.timeProvider, "timeProvider");
    throttler = checkNotNull(builder.throttler, "throttler");
    linkedHashLruCache =
        new RlsAsyncLruCache(
            rlsConfig.getCacheSizeBytes(),
            builder.evictionListener,
            scheduledExecutorService,
            timeProvider);
    lbPolicyConfig = checkNotNull(builder.lbPolicyConfig, "lbPolicyConfig");
    RlsRequestFactory requestFactory = new RlsRequestFactory(lbPolicyConfig.getRouteLookupConfig());
    rlsPicker = new RlsPicker(requestFactory);
    rlsChannel = helper.createResolvingOobChannel(builder.rlsConfig.getLookupService());
    rlsStub = RouteLookupServiceGrpc.newStub(rlsChannel);
    childLbResolvedAddressFactory =
        checkNotNull(builder.childLbResolvedAddressFactory, "childLbResolvedAddressFactory");
    backoffProvider = builder.backoffProvider;
    childLbHelperProvider =
        new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, rlsPicker);
    if (rlsConfig.getRequestProcessingStrategy()
        == RequestProcessingStrategy.SYNC_LOOKUP_CLIENT_SEES_ERROR) {
      childLbStatusListener = new BackoffRefreshListener();
    } else {
      childLbStatusListener = null;
    }
  }

  @CheckReturnValue
  private ListenableFuture<RouteLookupResponse> asyncRlsCall(RouteLookupRequest request) {
    final SettableFuture<RouteLookupResponse> response = SettableFuture.create();
    if (throttler.shouldThrottle()) {
      response.setException(new ThrottledException());
      return response;
    }
    rlsStub.withDeadlineAfter(callTimeoutNanos, TimeUnit.NANOSECONDS)
        .routeLookup(
            reqConverter.convert(request),
            new StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>() {
              @Override
              public void onNext(io.grpc.lookup.v1.RouteLookupResponse value) {
                response.set(respConverter.reverse().convert(value));
              }

              @Override
              public void onError(Throwable t) {
                response.setException(t);
                throttler.registerBackendResponse(false);
              }

              @Override
              public void onCompleted() {
                throttler.registerBackendResponse(true);
              }
            });
    return response;
  }

  /**
   * Returns async response of the {@code request}. The returned value can be in 3 different states;
   * cached, pending and backed-off due to error.
   */
  @CheckReturnValue
  public final CachedRouteLookupResponse get(final RouteLookupRequest request) {
    synchronized (lock) {
      final CacheEntry cacheEntry;
      cacheEntry = linkedHashLruCache.read(request);
      if (cacheEntry == null) {
        return handleNewRequest(request);
      }

      long now = timeProvider.currentTimeNanos();
      if (cacheEntry instanceof DataCacheEntry) {
        // cache hit, initiate async-refresh if entry is staled
        DataCacheEntry dataEntry = ((DataCacheEntry) cacheEntry);
        if (dataEntry.isStaled(now)) {
          dataEntry.maybeRefresh();
        }
      } else {
        return CachedRouteLookupResponse.backoffEntry((BackoffCacheEntry) cacheEntry);
      }
      return CachedRouteLookupResponse.dataEntry((DataCacheEntry) cacheEntry);
    }
  }

  /** Performs any pending maintenance operations needed by the cache. */
  public void close() {
    synchronized (lock) {
      // all childPolicyWrapper will be returned via AutoCleaningEvictionListener
      linkedHashLruCache.close();
      // TODO(creamsoup) maybe cancel all pending requests
      pendingCallCache.clear();
      rlsChannel.shutdown();
      rlsPicker.close();
    }
  }

  /**
   * Populates async cache entry for new request. This is only methods directly modifies the cache,
   * any status change is happening via event (async request finished, timed out, etc) in {@link
   * CacheEntry}.
   */
  private CachedRouteLookupResponse handleNewRequest(RouteLookupRequest request) {
    synchronized (lock) {
      PendingCacheEntry pendingEntry = pendingCallCache.get(request);
      if (pendingEntry != null) {
        return CachedRouteLookupResponse.pendingResponse(pendingEntry);
      }

      ListenableFuture<RouteLookupResponse> asyncCall = asyncRlsCall(request);
      if (!asyncCall.isDone()) {
        pendingEntry = new PendingCacheEntry(request, asyncCall);
        pendingCallCache.put(request, pendingEntry);
        return CachedRouteLookupResponse.pendingResponse(pendingEntry);
      } else {
        // async call returned finished future is most likely throttled
        try {
          RouteLookupResponse response = asyncCall.get();
          return CachedRouteLookupResponse.dataEntry(new DataCacheEntry(request, response));
        } catch (Exception e) {
          return CachedRouteLookupResponse.backoffEntry(
              new BackoffCacheEntry(
                  request,
                  Status.fromThrowable(e),
                  backoffProvider.get()));
        }
      }
    }
  }

  public void requestConnection() {
    rlsChannel.getState(true);
  }

  /** Viewer class for cached {@link RouteLookupResponse}. */
  static final class CachedRouteLookupResponse {
    private final RouteLookupRequest request;

    // Should only have 1 of following 3 cache entries
    @Nullable
    private final DataCacheEntry dataCacheEntry;
    @Nullable
    private final PendingCacheEntry pendingCacheEntry;
    @Nullable
    private final BackoffCacheEntry backoffCacheEntry;

    CachedRouteLookupResponse(
        RouteLookupRequest request,
        DataCacheEntry dataCacheEntry,
        PendingCacheEntry pendingCacheEntry,
        BackoffCacheEntry backoffCacheEntry) {
      this.request = checkNotNull(request, "request");
      this.dataCacheEntry = dataCacheEntry;
      this.pendingCacheEntry = pendingCacheEntry;
      this.backoffCacheEntry = backoffCacheEntry;
      checkState((dataCacheEntry != null ^ pendingCacheEntry != null ^ backoffCacheEntry != null)
          && !(dataCacheEntry != null && pendingCacheEntry != null && backoffCacheEntry != null),
          "Expected only 1 cache entry value provided");
    }

    static CachedRouteLookupResponse pendingResponse(PendingCacheEntry pendingEntry) {
      return new CachedRouteLookupResponse(pendingEntry.request, null, pendingEntry, null);
    }

    static CachedRouteLookupResponse backoffEntry(BackoffCacheEntry backoffEntry) {
      return new CachedRouteLookupResponse(backoffEntry.request, null, null, backoffEntry);
    }

    static CachedRouteLookupResponse dataEntry(DataCacheEntry dataEntry) {
      return new CachedRouteLookupResponse(dataEntry.request, dataEntry, null, null);
    }

    boolean hasValidData() {
      return dataCacheEntry != null;
    }

    @Nullable
    ChildPolicyWrapper getChildPolicyWrapper() {
      if (!hasValidData()) {
        return null;
      }
      return dataCacheEntry.getChildPolicyWrapper();
    }

    @Nullable
    public String getHeaderData() {
      if (!hasValidData()) {
        return null;
      }
      return dataCacheEntry.getHeaderData();
    }

    boolean hasError() {
      return backoffCacheEntry != null;
    }

    @Nullable
    Status getStatus() {
      if (!hasError()) {
        return null;
      }
      return backoffCacheEntry.getStatus();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("dataCacheEntry", dataCacheEntry)
          .add("pendingCacheEntry", pendingCacheEntry)
          .add("backoffCacheEntry", backoffCacheEntry)
          .toString();
    }
  }

  /** A pending cache entry when the async RouteLookup RPC is still on the fly. */
  private final class PendingCacheEntry {
    private final ListenableFuture<RouteLookupResponse> pendingCall;
    private final RouteLookupRequest request;
    private final BackoffPolicy backoffPolicy;

    PendingCacheEntry(
        RouteLookupRequest request, ListenableFuture<RouteLookupResponse> pendingCall) {
      this(request, pendingCall, null);
    }

    PendingCacheEntry(
        RouteLookupRequest request,
        ListenableFuture<RouteLookupResponse> pendingCall,
        @Nullable BackoffPolicy backoffPolicy) {
      this.request = checkNotNull(request, "request");
      this.pendingCall = pendingCall;
      this.backoffPolicy = backoffPolicy == null ? backoffProvider.get() : backoffPolicy;
      lbPolicyConfig
          .getLoadBalancingPolicy()
          .addPendingRequest(request, this.backoffPolicy);
      pendingCall.addListener(new Runnable() {
        @Override
        public void run() {
          handleDoneFuture();
        }
      }, synchronizationContext);
    }

    private void handleDoneFuture() {
      synchronized (lock) {
        pendingCallCache.remove(request);
        if (pendingCall.isCancelled()) {
          return;
        }

        try {
          transitionToDataEntry(pendingCall.get());
        } catch (Exception e) {
          if (e instanceof ThrottledException) {
            transitionToBackOff(Status.RESOURCE_EXHAUSTED.withCause(e));
          } else {
            transitionToBackOff(Status.fromThrowable(e));
          }
        }
      }
    }

    private void transitionToDataEntry(RouteLookupResponse routeLookupResponse) {
      synchronized (lock) {
        lbPolicyConfig
            .getLoadBalancingPolicy()
            .removePendingRequest(request);
        linkedHashLruCache.cache(request, new DataCacheEntry(request, routeLookupResponse));
      }
    }

    private void transitionToBackOff(Status status) {
      synchronized (lock) {
        pendingCallCache.remove(request);
        linkedHashLruCache.cache(request, new BackoffCacheEntry(request, status, backoffPolicy));
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("pendingCall", pendingCall)
          .add("backoffPolicy", backoffPolicy)
          .toString();
    }
  }

  /** Common cache entry data for {@link RlsAsyncLruCache}. */
  abstract class CacheEntry {

    protected final RouteLookupRequest request;

    CacheEntry(RouteLookupRequest request) {
      this.request = checkNotNull(request, "request");
    }

    abstract int getSizeBytes();

    boolean isExpired() {
      return isExpired(timeProvider.currentTimeNanos());
    }

    abstract boolean isExpired(long now);

    abstract void cleanup();
  }

  /** Implementation of {@link CacheEntry} contains valid data. */
  private final class DataCacheEntry extends CacheEntry {
    private final RouteLookupResponse response;
    private final long expireTime;
    private final long staleTime;
    private ChildPolicyWrapper childPolicyWrapper;

    DataCacheEntry(RouteLookupRequest request, final RouteLookupResponse response) {
      super(request);
      this.response = checkNotNull(response, "response");
      childPolicyWrapper = ChildPolicyWrapper.createOrGet(response.getTarget());
      long now = timeProvider.currentTimeNanos();
      expireTime = now + maxAgeNanos;
      staleTime = now + staleAgeNanos;

      if (childPolicyWrapper.getPicker() != null) {
        // using cached childPolicyWrapper
        updateLbState();
      } else {
        createChildLbPolicy(response);
        linkedHashLruCache.updateEntrySize(request);
      }
    }

    private void updateLbState() {
      checkState(
          childPolicyWrapper.getHelper() != null,
          "incomplete childPolicyWrapper found, this is a bug");
      childPolicyWrapper
          .getHelper()
          .updateBalancingState(
              childPolicyWrapper.getConnectivityStateInfo().getState(),
              childPolicyWrapper.getPicker());
    }

    private void createChildLbPolicy(RouteLookupResponse response) {
      ChildLoadBalancingPolicy childPolicy = lbPolicyConfig.getLoadBalancingPolicy();
      childPolicyWrapper.setChildPolicy(childPolicy);
      LoadBalancerProvider lbProvider = childPolicy.getEffectiveLbProvider();
      ConfigOrError lbConfig =
          lbProvider
              .parseLoadBalancingPolicyConfig(
                  childPolicy.getEffectiveChildPolicy(response.getTarget()));

      ChildPolicyReportingHelper childPolicyReportingHelper =
          new ChildPolicyReportingHelper(
              childLbHelperProvider, childPolicyWrapper, childLbStatusListener);
      childPolicyWrapper.setHelper(childPolicyReportingHelper);

      LoadBalancer lb = lbProvider.newLoadBalancer(childPolicyReportingHelper);
      lb.handleResolvedAddresses(childLbResolvedAddressFactory.create(lbConfig.getConfig()));
      lb.requestConnection();
    }

    /**
     * Refreshes cache entry by creating {@link PendingCacheEntry}. When the {@code
     * PendingCacheEntry} received data from RLS server, it will replace the data entry if valid
     * data still exists. Flow looks like following.
     *
     * <pre>
     * Timeline                       | async refresh
     *                                V put new cache (entry2)
     * entry1: Pending | hasValue | staled |
     * entry2:                        | OV | pending | hasValue | staled |
     * </pre>
     */
    void maybeRefresh() {
      synchronized (lock) {
        if (pendingCallCache.containsKey(request)) {
          // pending already requested
          return;
        }
        pendingCallCache.put(request, new PendingCacheEntry(request, asyncRlsCall(request)));
      }
    }

    @Nullable
    ChildPolicyWrapper getChildPolicyWrapper() {
      return childPolicyWrapper;
    }

    String getHeaderData() {
      return response.getHeaderData();
    }

    @Override
    int getSizeBytes() {
      // size of strings and java object overhead, actual memory usage is more than this.
      return (response.getTarget().length() + response.getHeaderData().length()) * 2 + 38 * 2;
    }

    @Override
    boolean isExpired(long now) {
      return expireTime <= now;
    }

    boolean isStaled(long now) {
      return staleTime <= now;
    }

    @Override
    void cleanup() {
      childPolicyWrapper.release();
      synchronized (lock) {
        linkedHashLruCache.invalidate(request);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("response", response)
          .add("expireTime", expireTime)
          .add("staleTime", staleTime)
          .add("childPolicyWrapper", childPolicyWrapper)
          .toString();
    }
  }

  /**
   * Implementation of {@link CacheEntry} contains error. This entry will transition to pending
   * status when the backoff time is expired.
   */
  private final class BackoffCacheEntry extends CacheEntry {

    private final Status status;
    private final ScheduledFuture<?> scheduledFuture;
    private final BackoffPolicy backoffPolicy;
    private final long expireMills;
    private boolean shutdown = false;

    BackoffCacheEntry(RouteLookupRequest request, Status status, BackoffPolicy backoffPolicy) {
      super(request);
      this.status = checkNotNull(status, "status");
      this.backoffPolicy = checkNotNull(backoffPolicy, "backoffPolicy");
      long delayNanos = backoffPolicy.nextBackoffNanos();
      this.expireMills = timeProvider.currentTimeNanos() + delayNanos;
      this.scheduledFuture = scheduledExecutorService.schedule(
          new Runnable() {
            @Override
            public void run() {
              transitionToPending();
            }
          },
          delayNanos,
          TimeUnit.NANOSECONDS);
    }

    /** Forcefully refreshes cache entry by ignoring the backoff timer. */
    void forceRefresh() {
      boolean cancelled = scheduledFuture.cancel(false);
      if (cancelled) {
        transitionToPending();
      }
    }

    private void transitionToPending() {
      if (shutdown) {
        return;
      }

      synchronized (lock) {
        ListenableFuture<RouteLookupResponse> call = asyncRlsCall(request);
        if (!call.isDone()) {
          PendingCacheEntry pendingEntry = new PendingCacheEntry(request, call, backoffPolicy);
          pendingCallCache.put(request, pendingEntry);
          linkedHashLruCache.invalidate(request);
        } else {
          try {
            RouteLookupResponse response = call.get();
            linkedHashLruCache.cache(request, new DataCacheEntry(request, response));
          } catch (Exception e) {
            linkedHashLruCache.cache(
                request,
                new BackoffCacheEntry(request, Status.fromThrowable(e), backoffPolicy));
          }
        }
      }
    }

    Status getStatus() {
      return status;
    }

    @Override
    int getSizeBytes() {
      return 0;
    }

    @Override
    boolean isExpired(long now) {
      return expireMills <= now;
    }

    @Override
    void cleanup() {
      shutdown = true;
      if (!scheduledFuture.isCancelled()) {
        scheduledFuture.cancel(true);
      }
      synchronized (lock) {
        linkedHashLruCache.invalidate(request);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("status", status)
          .add("backoffPolicy", backoffPolicy)
          .add("scheduledFuture", scheduledFuture)
          .toString();
    }
  }

  /** Returns a Builder for {@link CachingRlsLbClient}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** A Builder for {@link CachingRlsLbClient}. */
  public static final class Builder {

    private Helper helper;
    private RouteLookupConfig rlsConfig;
    private LbPolicyConfiguration lbPolicyConfig;
    private Throttler throttler = new HappyThrottler();
    private ChildLbResolvedAddressFactory childLbResolvedAddressFactory;
    private TimeProvider timeProvider = TimeProvider.SYSTEM_TIME_PROVIDER;
    private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener;
    private BackoffPolicy.Provider backoffProvider = new ExponentialBackoffPolicy.Provider();

    public Builder setHelper(Helper helper) {
      this.helper = checkNotNull(helper, "helper");
      return this;
    }

    public Builder setRlsConfig(RouteLookupConfig rlsConfig) {
      this.rlsConfig = checkNotNull(rlsConfig, "rlsConfig");
      return this;
    }

    public Builder setLbPolicyConfig(LbPolicyConfiguration lbPolicyConfig) {
      this.lbPolicyConfig = checkNotNull(lbPolicyConfig, "lbPolicyConfig");
      return this;
    }

    public Builder setThrottler(Throttler throttler) {
      this.throttler = checkNotNull(throttler, "throttler");
      return this;
    }

    /**
     * Sets a factory to create {@link ResolvedAddresses} for child load balancer.
     */
    public Builder setChildLbResolvedAddressesFactory(
        ChildLbResolvedAddressFactory childLbResolvedAddressFactory) {
      this.childLbResolvedAddressFactory =
          checkNotNull(childLbResolvedAddressFactory, "childLbResolvedAddressFactory");
      return this;
    }

    public Builder setTimeProvider(TimeProvider timeProvider) {
      this.timeProvider = checkNotNull(timeProvider, "timeProvider");
      return this;
    }

    public Builder setEvictionListener(
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener) {
      this.evictionListener = evictionListener;
      return this;
    }

    public Builder setBackoffProvider(BackoffPolicy.Provider provider) {
      this.backoffProvider = checkNotNull(provider, "provider");
      return this;
    }

    public CachingRlsLbClient build() {
      return new CachingRlsLbClient(this);
    }
  }

  /**
   * When any {@link CacheEntry} is evicted from {@link LruCache}, it performs {@link
   * CacheEntry#cleanup()} after original {@link EvictionListener} is finished.
   */
  private static final class AutoCleaningEvictionListener
      implements EvictionListener<RouteLookupRequest, CacheEntry> {

    private final EvictionListener<RouteLookupRequest, CacheEntry> delegate;

    AutoCleaningEvictionListener(
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onEviction(RouteLookupRequest key, CacheEntry value, EvictionType cause) {
      if (delegate != null) {
        delegate.onEviction(key, value, cause);
      }
      // performs cleanup after delegation
      value.cleanup();
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

  /** Implementation of {@link LinkedHashLruCache} for RLS. */
  private static final class RlsAsyncLruCache
      extends LinkedHashLruCache<RouteLookupRequest, CacheEntry> {

    RlsAsyncLruCache(long maxEstimatedSizeBytes,
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener,
        ScheduledExecutorService ses, TimeProvider timeProvider) {
      super(
          maxEstimatedSizeBytes,
          new AutoCleaningEvictionListener(evictionListener),
          1,
          TimeUnit.MINUTES,
          ses,
          timeProvider);
    }

    @Override
    protected boolean isExpired(RouteLookupRequest key, CacheEntry value, long nowNanos) {
      return value.isExpired();
    }

    @Override
    protected int estimateSizeOf(RouteLookupRequest key, CacheEntry value) {
      return value.getSizeBytes();
    }

    @Override
    protected boolean shouldInvalidateEldestEntry(
        RouteLookupRequest eldestKey, CacheEntry eldestValue) {
      // eldest entry should be evicted if size limit exceeded
      return true;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .toString();
    }
  }

  /**
   * LbStatusListener refreshes {@link BackoffCacheEntry} when lb state is changed to {@link
   * ConnectivityState#READY} from {@link ConnectivityState#TRANSIENT_FAILURE}.
   */
  private class BackoffRefreshListener implements ChildLbStatusListener {

    @Nullable
    private ConnectivityState prevState = null;

    @Override
    public void onStatusChanged(ConnectivityState newState) {
      if (prevState == ConnectivityState.TRANSIENT_FAILURE
          && newState == ConnectivityState.READY) {
        synchronized (lock) {
          for (CacheEntry value : linkedHashLruCache.values()) {
            if (value instanceof BackoffCacheEntry) {
              ((BackoffCacheEntry) value).forceRefresh();
            }
          }
        }
      }
      prevState = newState;
    }
  }

  /** A header will be added when RLS server respond with additional header data. */
  public static final Metadata.Key<String> RLS_DATA_KEY =
      Metadata.Key.of("X-Google-RLS-Data", Metadata.ASCII_STRING_MARSHALLER);

  final class RlsPicker extends SubchannelPicker {

    private final ChildLoadBalancerHelperProvider childLbHelperProvider;
    private final RlsRequestFactory requestFactory;

    RlsPicker(RlsRequestFactory requestFactory) {
      this.requestFactory = checkNotNull(requestFactory, "requestFactory");
      helper.updateBalancingState(ConnectivityState.CONNECTING, this);
      this.childLbHelperProvider =
          new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, this);
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      String[] methodName = args.getMethodDescriptor().getFullMethodName().split("/", 2);
      RouteLookupRequest request =
          requestFactory.create(methodName[0], methodName[1], args.getHeaders());
      final CachedRouteLookupResponse response = CachingRlsLbClient.this.get(request);

      PickSubchannelArgs rlsAppliedArgs = getApplyRlsHeader(args, response);
      if (response.hasValidData()) {
        ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
        ConnectivityState connectivityState =
            childPolicyWrapper.getConnectivityStateInfo().getState();
        switch (connectivityState) {
          case CONNECTING:
            return PickResult.withNoResult();
          case IDLE:
            if (childPolicyWrapper.getPicker() == null) {
              return PickResult.withNoResult();
            }
            // fall through
          case READY:
            return childPolicyWrapper.getPicker().pickSubchannel(rlsAppliedArgs);
          case TRANSIENT_FAILURE:
            return handleError(rlsAppliedArgs, Status.INTERNAL);
          case SHUTDOWN:
          default:
            return handleError(rlsAppliedArgs, Status.ABORTED);
        }
      } else if (response.hasError()) {
        return handleError(rlsAppliedArgs, response.getStatus());
      } else {
        return PickResult.withNoResult();
      }
    }

    private PickSubchannelArgs getApplyRlsHeader(PickSubchannelArgs args, CachedRouteLookupResponse response) {
      if (response.getHeaderData() == null || response.getHeaderData().isEmpty()) {
        return args;
      }

      Metadata headers = new Metadata();
      headers.merge(args.getHeaders());
      headers.put(RLS_DATA_KEY, response.getHeaderData());
      return new PickSubchannelArgsImpl(args.getMethodDescriptor(), headers, args.getCallOptions());
    }

    private PickResult handleError(PickSubchannelArgs args, Status cause) {
      RequestProcessingStrategy strategy =
          lbPolicyConfig.getRouteLookupConfig().getRequestProcessingStrategy();
      switch (strategy) {
        case SYNC_LOOKUP_CLIENT_SEES_ERROR:
          return PickResult.withError(cause);
        case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
          cause.asException().printStackTrace();
          return useFallback(args);
        default:
          throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
      }
    }

    private ChildPolicyWrapper fallbackChildPolicyWrapper;

    /** Uses Subchannel connected to default target. */
    private PickResult useFallback(PickSubchannelArgs args) {
      String defaultTarget = lbPolicyConfig.getRouteLookupConfig().getDefaultTarget();
      if (fallbackChildPolicyWrapper == null
          || !fallbackChildPolicyWrapper.getTarget().equals(defaultTarget)) {
        try {
          startFallbackChildPolicy()
              .await(
                  lbPolicyConfig.getRouteLookupConfig().getLookupServiceTimeoutInMillis(),
                  TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return PickResult.withError(Status.ABORTED.withDescription("interrupted"));
        } catch (Exception e) {
          return PickResult.withError(Status.fromThrowable(e));
        }
      }
      SubchannelPicker picker = fallbackChildPolicyWrapper.getPicker();
      switch (fallbackChildPolicyWrapper.getConnectivityStateInfo().getState()) {
        case CONNECTING:
          return PickResult.withNoResult();
        case TRANSIENT_FAILURE:
          // fall through
        case SHUTDOWN:
          return
              PickResult
                  .withError(fallbackChildPolicyWrapper.getConnectivityStateInfo().getStatus());
        case IDLE:
          if (picker == null) {
            return PickResult.withNoResult();
          }
          // fall through
        case READY:
          return picker.pickSubchannel(args);
        default:
          throw new AssertionError();
      }
    }

    private CountDownLatch startFallbackChildPolicy() {
      String defaultTarget = lbPolicyConfig.getRouteLookupConfig().getDefaultTarget();
      fallbackChildPolicyWrapper = ChildPolicyWrapper.createOrGet(defaultTarget);
      final CountDownLatch readyLatch = new CountDownLatch(1);

      LoadBalancerProvider lbProvider =
          lbPolicyConfig.getLoadBalancingPolicy().getEffectiveLbProvider();
      ChildPolicyReportingHelper childPolicyReportingHelper =
          new ChildPolicyReportingHelper(childLbHelperProvider, fallbackChildPolicyWrapper);
      final LoadBalancer lb =
          lbProvider.newLoadBalancer(childPolicyReportingHelper);
      final ConfigOrError lbConfig =
          lbProvider
              .parseLoadBalancingPolicyConfig(
                  lbPolicyConfig
                      .getLoadBalancingPolicy()
                      .getEffectiveChildPolicy(defaultTarget));
      fallbackChildPolicyWrapper.setChildPolicy(lbPolicyConfig.getLoadBalancingPolicy());
      helper.getSynchronizationContext().execute(
          new Runnable() {
            @Override
            public void run() {
              lb.handleResolvedAddresses(
                  childLbResolvedAddressFactory.create(lbConfig.getConfig()));
              lb.requestConnection();
              readyLatch.countDown();
            }
          });
      return readyLatch;
    }

    void close() {
      if (fallbackChildPolicyWrapper != null) {
        fallbackChildPolicyWrapper.release();
      }
    }
  }
}