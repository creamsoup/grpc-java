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

import com.google.common.base.Converter;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.AdaptiveThrottler.SystemTicker;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import io.grpc.rls.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import io.grpc.rls.RlsLoadBalancer.ChildLbResolvedAddressFactory;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.Throttler.ThrottledException;
import io.grpc.stub.StreamObserver;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An AsyncRlsCache is a cache for rls service. All the methods in this class are non blocking.
 * This async behavior is reflected to the {@link #get(RouteLookupRequest)} method, when the cache
 * is requested but not fully populated, it returns uncompleted {@link ListenableFuture} which
 * allows the users to wait or ignore until the computation is completed.
 *
 * <p>On top of regular cache behavior, it supports max age and stale state of cache value. The key
 * will be expired when max age is reached. When the cached entry is staled (age of cache is in
 * between staled and max), AsyncRequestCache will asynchronously refresh the entry.
 */
@ThreadSafe
final class AsyncCachingRlsClient {

  private static final Converter<RouteLookupRequest, io.grpc.lookup.v1.RouteLookupRequest>
      reqConverter = new RlsProtoConverters.RouteLookupRequestConverter().reverse();
  private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
      respConverter = new RouteLookupResponseConverter().reverse();

  private final Object lock = new Object();

  private final ScheduledExecutorService scheduledExecutorService;
  // LRU cache based on access order (BACKOFF and actual data will be here)
  private final LinkedHashLruCache<RouteLookupRequest, CacheEntry> linkedHashLruCache;
  // any RPC on the fly will cached in this map
  private final Map<RouteLookupRequest, PendingCacheEntry> pendingCallCache = new HashMap<>();
  private final Executor syncronizedContext;
  private final Ticker ticker;
  private final Throttler throttler;
  private final BackoffPolicy.Provider backoffProvider;

  private final long maxAgeMillis;
  private final long staleAgeMillis;
  private final long callTimeoutMillis;
  private final long maxSizeBytes;
  private final Helper helper;
  private final LbPolicyConfiguration lbPolicyConfig;
  private final ManagedChannel channel;
  private final ChildLbResolvedAddressFactory childLbResolvedAddressFactory;
  private final RouteLookupServiceStub stub;
  private final RlsPicker rlsPicker;
  private final ChildLoadBalancerHelperProvider childLbHelperProvider;

  AsyncCachingRlsClient(final Builder builder) {
    this.helper = checkNotNull(builder.helper, "helper");
    this.scheduledExecutorService = helper.getScheduledExecutorService();
    this.syncronizedContext = helper.getSynchronizationContext();
    checkState(builder.maxAgeMillis > 0, "maxAgeMillis should be positive");
    checkState(builder.staleAgeMillis > 0, "staleAgeMillis should be positive");
    checkState(
        builder.maxAgeMillis >= builder.staleAgeMillis,
        "maxAgeMillis should be greater than equals to staleAgeMillis");
    checkState(builder.callTimeoutMillis > 0, "callTimeoutMillis should be positive");
    this.maxAgeMillis = builder.maxAgeMillis;
    this.staleAgeMillis = builder.staleAgeMillis;
    this.callTimeoutMillis = builder.callTimeoutMillis;
    this.ticker = checkNotNull(builder.ticker, "ticker");
    this.maxSizeBytes = (int) builder.maxCacheSizeBytes;
    this.throttler = checkNotNull(builder.throttler, "throttler");
    this.linkedHashLruCache =
        new RlsAsyncLruCache(
            maxSizeBytes, builder.evictionListener, scheduledExecutorService, ticker);
    this.lbPolicyConfig = checkNotNull(builder.lbPolicyConfig, "lbPolicyConfig");
    this.channel = checkNotNull(builder.channel, "subchannel");
    this.stub = RouteLookupServiceGrpc.newStub(channel);
    this.childLbResolvedAddressFactory =
        checkNotNull(builder.childLbResolvedAddressFactory, "childLbResolvedAddressFactory");
    this.backoffProvider = builder.backoffProvider;
    this.rlsPicker = new RlsPicker(lbPolicyConfig, this, helper, childLbResolvedAddressFactory);
    this.childLbHelperProvider = new ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider(helper, rlsPicker);
  }

  @CheckReturnValue
  private ListenableFuture<RouteLookupResponse> asyncRlsCall(RouteLookupRequest request) {
    final SettableFuture<RouteLookupResponse> response = SettableFuture.create();
    if (stub == null) {
      response.setException(new RuntimeException("OobChannel is not ready yet"));
      return response;
    }
    if (throttler.shouldThrottle()) {
      response.setException(new ThrottledException());
      return response;
    }
    io.grpc.lookup.v1.RouteLookupRequest rlsRequest = reqConverter.convert(request);
    System.out.println("time: " + System.currentTimeMillis());
    System.out.println("channel status: " + channel.getState(false));
    stub.withDeadlineAfter(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .routeLookup(
            rlsRequest,
            new StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>() {
              @Override
              public void onNext(io.grpc.lookup.v1.RouteLookupResponse value) {
                System.out.println("woohoo: " + value);
                response.set(respConverter.reverse().convert(value));
              }

              @Override
              public void onError(Throwable t) {
                new RuntimeException("oops " + System.currentTimeMillis(), t).printStackTrace(System.out);
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
   * Returns the value associated with {@code request} in this cache, obtaining that value from
   * {@code loader} if necessary. The method improves upon the conventional "if cached, return;
   * otherwise create, cache and return" pattern. If the cache was not present, it returns a future
   * value.
   */
  @CheckReturnValue
  public final CachedResponse get(final RouteLookupRequest request) {
    synchronized (lock) {
      final CacheEntry cacheEntry;
      cacheEntry = linkedHashLruCache.read(request);
      if (cacheEntry == null) {
        return handleNewRequest(request);
      }

      long now = ticker.nowInMillis();
      if (cacheEntry.hasData()) {
        // cache hit, initiate async-refresh if entry is staled
        DataCacheEntry dataEntry = ((DataCacheEntry) cacheEntry);
        if (dataEntry.isStaled(now)) {
          dataEntry.maybeRefresh();
        }
      } else {
        return CachedResponse.backoffEntry((BackoffCacheEntry) cacheEntry);
      }
      return CachedResponse.dataEntry((DataCacheEntry) cacheEntry);
    }
  }

  /** Performs any pending maintenance operations needed by the cache. */
  public synchronized void close() {
    linkedHashLruCache.close();
  }

  /**
   * Populates async cache entry for new request. This is only methods directly modifies the cache,
   * any status change is happening via event (async request finished, timed out, etc) in {@link
   * CacheEntry}.
   */
  private CachedResponse handleNewRequest(RouteLookupRequest request) {
    synchronized (lock) {
      PendingCacheEntry pendingEntry = pendingCallCache.get(request);
      if (pendingEntry != null) {
        return CachedResponse.pendingResponse(pendingEntry);
      }

      ListenableFuture<RouteLookupResponse> asyncCall = asyncRlsCall(request);
      if (!asyncCall.isDone()) {
        pendingEntry = new PendingCacheEntry(request, asyncCall);
        pendingCallCache.put(request, pendingEntry);
        return CachedResponse.pendingResponse(pendingEntry);
      } else {
        try {
          RouteLookupResponse response = asyncCall.get();
          return CachedResponse.dataEntry(new DataCacheEntry(request, response));
        } catch (Exception e) {
          return CachedResponse.backoffEntry(
              new BackoffCacheEntry(
                  request,
                  Status.fromThrowable(e),
                  backoffProvider.get()));
        }
      }
    }
  }

  /** Cached response of RouteLookupRequest. */
  static final class CachedResponse {
    private final RouteLookupRequest request;

    // Should only have 1 of following 3 cache entry
    @Nullable
    private final DataCacheEntry dataCacheEntry;
    @Nullable
    private final PendingCacheEntry pendingCacheEntry;
    @Nullable
    private final BackoffCacheEntry backoffCacheEntry;

    private CachedResponse(
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

    /** Creates a {@link CachedResponse} from pending cache entry. */
    static CachedResponse pendingResponse(PendingCacheEntry pendingEntry) {
      return new CachedResponse(pendingEntry.request, null, pendingEntry, null);
    }

    /** Creates a {@link CachedResponse} from error cache entry. */
    static CachedResponse backoffEntry(BackoffCacheEntry backoffEntry) {
      return new CachedResponse(backoffEntry.request, null, null, backoffEntry);
    }

    /** Creates a {@link CachedResponse} from valid data cache entry. */
    static CachedResponse dataEntry(DataCacheEntry dataEntry) {
      return new CachedResponse(dataEntry.request, dataEntry, null, null);
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

  /** Async cache entry where the RPC is still on the fly. */
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
      pendingCall.addListener(new Runnable() {
        @Override
        public void run() {
          handleDoneFuture();
        }
      }, syncronizedContext);
    }

    private void handleDoneFuture() {
      if (pendingCall.isCancelled()) {
        return;
      }

      synchronized (lock) {
        try {
          transitionToData(pendingCall.get());
        } catch (Exception e) {
          if (e instanceof ThrottledException) {
            transitionToBackOff(Status.RESOURCE_EXHAUSTED.withCause(e));
          } else {
            transitionToBackOff(Status.fromThrowable(e));
          }
        }
      }
    }

    private void transitionToData(RouteLookupResponse routeLookupResponse) {
      pendingCallCache.remove(request);
      linkedHashLruCache.cache(request, new DataCacheEntry(request, routeLookupResponse));
    }

    private void transitionToBackOff(Status status) {
      pendingCallCache.remove(request);
      linkedHashLruCache.cache(request, new BackoffCacheEntry(request, status, backoffPolicy));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("pendingCall", pendingCall)
          .add("request", request)
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

    abstract boolean hasData();

    abstract int getSizeBytes();

    boolean isExpired() {
      return isExpired(ticker.nowInMillis());
    }

    abstract boolean isExpired(long now);

    boolean isStaled() {
      return isStaled(ticker.nowInMillis());
    }

    abstract boolean isStaled(long now);

    abstract void cleanup();
  }

  /** Implementation of {@link CacheEntry} contains valid data. */
  private final class DataCacheEntry extends CacheEntry {
    private final RouteLookupResponse response;
    @Nullable
    private final String headerData;
    private final long expireTime;
    private final long staleTime;
    private final ChildPolicyWrapper childPolicyWrapper;

    DataCacheEntry(RouteLookupRequest request, final RouteLookupResponse response) {
      super(request);
      this.response = checkNotNull(response, "response");
      headerData = response.getHeaderData();

      childPolicyWrapper = ChildPolicyWrapper.createOrGet(response.getTarget());
      long now = ticker.nowInMillis();
      expireTime = now + maxAgeMillis;
      staleTime = now + staleAgeMillis;
      linkedHashLruCache.updateEntrySize(request);

      if (childPolicyWrapper.getPicker() == null) {
        //TODO refactor this to util method (so fallback can also use it)
        // set picker etc
        childPolicyWrapper.setChildPolicy(lbPolicyConfig.getLoadBalancingPolicy());
        LoadBalancerProvider lbProvider = childPolicyWrapper
            .getChildPolicy()
            .getEffectiveLbProvider();
        ChildPolicyReportingHelper childPolicyReportingHelper =
            new ChildPolicyReportingHelper(childLbHelperProvider.forTarget(response.getTarget()), childPolicyWrapper);
        LoadBalancer lb =
            lbProvider.newLoadBalancer(childPolicyReportingHelper);
        childPolicyWrapper.setLoadBalancer(lb);

        ConfigOrError lbConfig = lbProvider
            .parseLoadBalancingPolicyConfig(
                childPolicyWrapper.getChildPolicy().getEffectiveChildPolicy(response.getTarget()));
        ResolvedAddresses resolvedAddresses = childLbResolvedAddressFactory
            .create(lbConfig.getConfig());
        lb.handleResolvedAddresses(resolvedAddresses);
        lb.requestConnection();
      } else {
        System.out.println("reusing childPolicyWrapper for " + response);
        childPolicyWrapper
            .getHelper()
            .updateBalancingState(
                childPolicyWrapper.getConnectivityState(), childPolicyWrapper.getPicker());
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
    boolean hasData() {
      return true;
    }

    @Override
    int getSizeBytes() {
      return (response.getTarget().length() + response.getHeaderData().length()) * 2 + 38 * 2;
    }

    @Override
    void cleanup() {
      if (childPolicyWrapper != null) {
        childPolicyWrapper.release();
      }
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

    @Override
    boolean isExpired() {
      return isExpired(ticker.nowInMillis());
    }

    @Override
    boolean isExpired(long now) {
      return expireTime <= now;
    }

    @Override
    boolean isStaled(long now) {
      return staleTime <= now;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("response", response)
          .add("headerData", headerData)
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
    private volatile boolean shutdown = false;

    BackoffCacheEntry(RouteLookupRequest request, Status status, BackoffPolicy backoffPolicy) {
      super(request);
      this.status = checkNotNull(status, "status");
      this.backoffPolicy = checkNotNull(backoffPolicy, "backoffPolicy");
      //TODO removeme
      status.asException().printStackTrace(System.out);
      long delay = backoffPolicy.nextBackoffNanos();
      this.expireMills = ticker.nowInMillis() + TimeUnit.NANOSECONDS.toMillis(delay);
      this.scheduledFuture = scheduledExecutorService.schedule(
          new Runnable() {
            @Override
            public void run() {
              transitionToPending();
            }
          },
          delay,
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
    boolean hasData() {
      return false;
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
    boolean isStaled(long now) {
      return isExpired(now);
    }

    @Override
    void cleanup() {
      shutdown = true;
      if (!scheduledFuture.isCancelled()) {
        scheduledFuture.cancel(true);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("status", status)
          .add("backoffPolicy", backoffPolicy)
          .add("scheduledFuture", scheduledFuture)
          .toString();
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

  /** Returns a Builder for {@link AsyncCachingRlsClient}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** A Builder for {@link AsyncCachingRlsClient}. */
  public static final class Builder {

    private Helper helper;
    private LbPolicyConfiguration lbPolicyConfig;
    private long maxCacheSizeBytes = 100 * 1024 * 1024; // 100 MB
    private long maxAgeMillis = TimeUnit.MINUTES.toMillis(5);
    private long staleAgeMillis = TimeUnit.MINUTES.toMillis(3);
    private long callTimeoutMillis = TimeUnit.SECONDS.toMillis(5);
    private Ticker ticker = new SystemTicker();
    private Throttler throttler = new HappyThrottler();
    private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener = null;
    private ManagedChannel channel;
    private ChildLbResolvedAddressFactory childLbResolvedAddressFactory;
    private BackoffPolicy.Provider backoffProvider = new ExponentialBackoffPolicy.Provider();

    public Builder setMaxCacheSizeBytes(long maxCacheSizeBytes) {
      this.maxCacheSizeBytes = maxCacheSizeBytes;
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

    public Builder setCallTimeoutMillis(long callTimeoutMillis) {
      this.callTimeoutMillis = callTimeoutMillis;
      return this;
    }

    public Builder setTicker(Ticker ticker) {
      this.ticker = checkNotNull(ticker, "ticker");
      return this;
    }

    public Builder setThrottler(Throttler throttler) {
      this.throttler = checkNotNull(throttler, "throttler");
      return this;
    }

    public Builder setEvictionListener(
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener) {
      this.evictionListener = evictionListener;
      return this;
    }

    public Builder setHelper(Helper helper) {
      this.helper = checkNotNull(helper, "helper");
      return this;
    }

    public Builder setLbPolicyConfig(LbPolicyConfiguration lbPolicyConfig) {
      this.lbPolicyConfig = checkNotNull(lbPolicyConfig, "lbPolicyConfig");
      return this;
    }

    public Builder setChannel(ManagedChannel rlsServerChannel) {
      this.channel = checkNotNull(rlsServerChannel, "rlsServerChannel");
      return this;
    }

    public Builder setChildLbResolvedAddressesFactory(
        ChildLbResolvedAddressFactory childLbResolvedAddressFactory) {
      this.childLbResolvedAddressFactory =
          checkNotNull(childLbResolvedAddressFactory, "childLbResolvedAddressFactory");
      return this;
    }

    public Builder setBackoffProvider(BackoffPolicy.Provider provider) {
      this.backoffProvider = checkNotNull(provider, "provider");
      return this;
    }

    public AsyncCachingRlsClient build() {
      return new AsyncCachingRlsClient(this);
    }
  }

  /** A delegating {@link LoadBalancer.Helper} populates {@link ChildPolicyWrapper}. */
  static class ChildPolicyReportingHelper extends ForwardingLoadBalancerHelper {

    private final ChildLoadBalancerHelper delegate;
    private final ChildPolicyWrapper childPolicyWrapper;

    public ChildPolicyReportingHelper(
        ChildLoadBalancerHelper delegate,
        ChildPolicyWrapper childPolicyWrapper) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.childPolicyWrapper = checkNotNull(childPolicyWrapper, "childPolicyWrapper");
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      childPolicyWrapper.setPicker(newPicker);
      childPolicyWrapper.setConnectivityState(newState);
      // super is doing the updateBalancingState
      super.updateBalancingState(newState, newPicker);
    }

    @Override
    @SuppressWarnings("deprecation")
    public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
      return createSubchannel(CreateSubchannelArgs.newBuilder()
          .setAddresses(addrs)
          .setAttributes(attrs)
          .build());
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      final Subchannel sc = super.createSubchannel(args);
      childPolicyWrapper.setSubchannel(sc);
      return super.createSubchannel(args);
    }
  }

  /** Implementation of {@link LinkedHashLruCache} for RLS. */
  private static final class RlsAsyncLruCache
      extends LinkedHashLruCache<RouteLookupRequest, CacheEntry> {

    RlsAsyncLruCache(long maxEstimatedSizeBytes,
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener,
        ScheduledExecutorService ses, Ticker ticker) {
      super(
          maxEstimatedSizeBytes,
          new AutoCleaningEvictionListener(evictionListener),
          1,
          TimeUnit.MINUTES,
          ses,
          ticker);
    }

    @Override
    protected boolean isExpired(RouteLookupRequest key, CacheEntry value, long nowInMillis) {
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
}
