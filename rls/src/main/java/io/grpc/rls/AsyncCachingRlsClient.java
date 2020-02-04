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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.AtomicBackoff;
import io.grpc.internal.AtomicBackoff.State;
import io.grpc.internal.ObjectPool;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.Throttler.ThrottledException;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An AsyncRlsCache is a cache for rls service. All the methods in this class are non
 * blocking. This async behavior is reflected to the {@link #get(RouteLookupRequest)} method, when the
 * cache is requested but not fully populated, it returns uncompleted {@link ListenableFuture} which
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
  private static final long DEFAULT_BACKOFF_TIME_MILLIS = 1_000;
  private static final long DEFAULT_BACKOFF_EXPIRATION_TIME_MILLIS = 10_000;

  private final ScheduledExecutorService scheduledExecutorService;
  // LRU cache based on access order (BACKOFF and actual data will be here)
  private final LinkedHashLruCache<RouteLookupRequest, CacheEntry> linkedHashLruCache;
  // anything on the fly will be here.
  private final Map<RouteLookupRequest, PendingCacheEntry> pendingCallCache;
  private final Executor executor;
  private final Ticker ticker;
  private final Throttler throttler;

  private final ManagedChannel channel;
  private final RouteLookupServiceStub stub;

  private final long maxAgeMillis;
  private final long staleAgeMillis;
  private final long callTimeoutMillis;
  private final long maxSizeBytes;

  // TODO: possibly have a sorted map of expire entry to proactively cleanup.

  // TODO: use builder (possibly make a copy constructor as well for
  AsyncCachingRlsClient(
      ManagedChannel channel,
      ScheduledExecutorService scheduledExecutorService,
      Executor executor,
      long maxAgeMillis,
      long staleAgeMillis,
      long maxCacheSize,
      long callTimeoutMillis,
      Ticker ticker,
      Throttler throttler,
      EvictionListener<RouteLookupRequest, CacheEntry> evictionListener) {
    this.channel = checkNotNull(channel, "channel");
    this.stub = RouteLookupServiceGrpc.newStub(channel);
    this.scheduledExecutorService =
        checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    this.executor = checkNotNull(executor, "executor");
    checkState(maxAgeMillis > 0, "maxAgeMillis should be positive");
    checkState(staleAgeMillis > 0, "staleAgeMillis should be positive");
    checkState(
        maxAgeMillis >= staleAgeMillis,
        "maxAgeMillis should be greater than equals to staleAgeMillis");
    checkState(callTimeoutMillis > 0, "callTimeoutMillis should be positive");
    this.maxAgeMillis = maxAgeMillis;
    this.staleAgeMillis = staleAgeMillis;
    this.callTimeoutMillis = callTimeoutMillis;
    this.ticker = checkNotNull(ticker, "ticker");
    this.maxSizeBytes = (int) maxCacheSize;
    this.throttler = throttler != null ? throttler : new HappyThrottler();
    this.linkedHashLruCache =  new LinkedHashLruCache<RouteLookupRequest, CacheEntry>(
        maxSizeBytes,
        new AutoCleaningEvictionListener(evictionListener),
        1,
        TimeUnit.MINUTES,
        scheduledExecutorService,
        ticker) {

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
    };
    this.pendingCallCache = new HashMap<>();
  }

  @CheckReturnValue
  private ListenableFuture<RouteLookupResponse> asyncRlsCall(RouteLookupRequest request) {
    final SettableFuture<RouteLookupResponse> response = SettableFuture.create();
    if (throttler.shouldThrottle()) {
      response.setException(new ThrottledException());
      return response;
    }
    io.grpc.lookup.v1.RouteLookupRequest rlsRequest = reqConverter.convert(request);
    stub.withDeadlineAfter(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .routeLookup(rlsRequest, new StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>() {
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
   * Returns the value associated with {@code request} in this cache, obtaining that value from
   * {@code loader} if necessary. The method improves upon the conventional "if cached, return;
   * otherwise create, cache and return" pattern. If the cache was not present, it returns a future
   * value.
   */
  @CheckReturnValue
  public final synchronized CachedResponse get(final RouteLookupRequest request) {
    final CacheEntry cacheEntry;
    cacheEntry = linkedHashLruCache.read(request);
    if (cacheEntry == null) {
      return CachedResponse.pendingResponse(createOrGetPendingCacheEntry(request));
    }

    long now = ticker.nowInMillis();
    if (cacheEntry.hasData()) {
      // cache hit, check if entry is staled to fire async-refresh
      DataCacheEntry dataEntry = ((DataCacheEntry) cacheEntry);
      if (dataEntry.isStaled(now)) {
        dataEntry.maybeRefresh();
      }
    } else {
      return CachedResponse.backoffEntry((BackoffCacheEntry) cacheEntry);
    }
    return CachedResponse.dataEntry((DataCacheEntry) cacheEntry);
  }

  /** Performs any pending maintenance operations needed by the cache. */
  public synchronized void close() {
    linkedHashLruCache.close();
    channel.shutdown();
  }

  private PendingCacheEntry createOrGetPendingCacheEntry(RouteLookupRequest request) {
    // all the put is though this method, perform clean up
    PendingCacheEntry pendingEntry = pendingCallCache.get(request);
    if (pendingEntry == null) {
      pendingEntry = new PendingCacheEntry(request,  asyncRlsCall(request));
      this.pendingCallCache.put(request, pendingEntry);
    }
    return pendingEntry;
  }

  /**
   *
   */
  public final class PendingCacheEntry {
    final ListenableFuture<RouteLookupResponse> pendingCall;
    private final RouteLookupRequest request;
    private final AtomicBackoff backoff;
    private final long backoffExpirationTime;

    public PendingCacheEntry(
        RouteLookupRequest request,
        final ListenableFuture<RouteLookupResponse> pendingCall) {
      this(
          request,
          pendingCall,
          new AtomicBackoff("rls:" + request.getServer(), DEFAULT_BACKOFF_TIME_MILLIS),
          DEFAULT_BACKOFF_EXPIRATION_TIME_MILLIS);
    }

    public PendingCacheEntry(
        RouteLookupRequest request,
        ListenableFuture<RouteLookupResponse> pendingCall,
        AtomicBackoff backoff,
        long backoffExpirationTime) {
      this.request = checkNotNull(request, "request");
      this.pendingCall = pendingCall;
      this.backoff = checkNotNull(backoff, "backoff");
      pendingCall.addListener(new Runnable() {
        @Override
        public void run() {
          handleDoneFuture();
        }
      }, executor);
      this.backoffExpirationTime = backoffExpirationTime;
    }

    private void handleDoneFuture() {
      if (pendingCall.isCancelled()) {
        return;
      }

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

    private void transitionToData(RouteLookupResponse routeLookupResponse) {
      pendingCallCache.remove(request);
      linkedHashLruCache
          .cache(request, new DataCacheEntry(request, routeLookupResponse));
    }

    private void transitionToBackOff(Status status) {
      pendingCallCache.remove(request);
      linkedHashLruCache
          .cache(request, new BackoffCacheEntry(request, status, backoff, backoffExpirationTime));
    }

    public int getSizeBytes() {
      return 0;
    }

    public void cleanup() {
      // todo maybe cancel listenable future?
    }
  }

  // TODO possibly return wrapper like this
  public static final class CachedResponse {
    private final RouteLookupRequest request;

    // contains one of those 3
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
    }

    static CachedResponse pendingResponse(PendingCacheEntry pendingEntry) {
      return new CachedResponse(pendingEntry.request, null, pendingEntry, null);
    }

    static CachedResponse backoffEntry(BackoffCacheEntry backoffEntry) {
      return new CachedResponse(backoffEntry.request, null, null, backoffEntry);
    }

    static CachedResponse dataEntry(DataCacheEntry dataEntry) {
      return new CachedResponse(dataEntry.request, dataEntry, null, null);
    }

    public boolean hasValidData() {
      return dataCacheEntry != null;
    }

    public ChildPolicyWrapper getChildPolicyWrapper() {
      checkState(hasValidData(), "should only accessed when has valid data");
      return dataCacheEntry.getChildPolicyWrapper();
    }

    @Nullable
    public String getHeaderData() {
      checkState(hasValidData(), "should only accessed when has valid data");
      return dataCacheEntry.getHeaderData();
    }

    public boolean hasError() {
      return backoffCacheEntry != null;
    }

    public Status getStatus() {
      checkState(hasError(), "must access getStatus when response is error");
      return backoffCacheEntry.getStatus();
    }
  }

  /**
   *
   */
  public abstract class CacheEntry {

    protected final RouteLookupRequest request;

    public CacheEntry(RouteLookupRequest request) {
      this.request = checkNotNull(request, "request");
    }

    public abstract boolean hasData();

    public abstract int getSizeBytes();

    boolean isExpired() {
      return isExpired(ticker.nowInMillis());
    }

    abstract boolean isExpired(long now);

    boolean isStaled() {
      return isStaled(ticker.nowInMillis());
    }

    public abstract boolean isStaled(long now);

    abstract void cleanup();
  }

  /**
   *
   */
  public final class DataCacheEntry extends CacheEntry {
    final RouteLookupResponse response;
    final @Nullable String headerData;
    final long expireTime;
    final long staleTime;

    @Nullable ObjectPool<ChildPolicyWrapper> childPolicyWrapperPool;
    ChildPolicyWrapper childPolicyWrapper;

    DataCacheEntry(
        RouteLookupRequest request,
        RouteLookupResponse response) {
      super(request);
      this.response = checkNotNull(response, "response");
      headerData = response.getHeaderData();
      childPolicyWrapperPool = ChildPolicyWrapper.createOrGet(request.getServer());
      childPolicyWrapper = childPolicyWrapperPool.getObject();
      long now = ticker.nowInMillis();
      expireTime = now + maxAgeMillis;
      staleTime = now + staleAgeMillis;
      linkedHashLruCache.updateEntrySize(request);
    }

    @Nullable
    public ChildPolicyWrapper getChildPolicyWrapper() {
      return childPolicyWrapper;
    }

    public String getHeaderData() {
      return response.getHeaderData();
    }

    @Override
    public boolean hasData() {
      return true;
    }

    @Override
    public int getSizeBytes() {
      return (response.getTarget().length() + response.getHeaderData().length()) * 2 + 38 * 2;
    }

    @Override
    public void cleanup() {
      if (childPolicyWrapper != null) {
        childPolicyWrapperPool.returnObject(childPolicyWrapper);
        childPolicyWrapperPool = null;
        childPolicyWrapper = null;
      }
    }

    /**
     * Refreshes cache entry, and replacing it immediately. Replaced cache entry will serve the staled
     * value until old cache is expired or new value is available whichever happens first.
     *
     * <pre>
     * Timeline                       | async refresh
     *                                V put new cache (entry2)
     * entry1: Pending | hasValue | staled |
     * entry2:                        | OV | pending | hasValue | staled |
     * </pre>
     */
    public void maybeRefresh() {
      if (pendingCallCache.containsKey(request)) {
        // pending already requested
        return;
      }
      pendingCallCache.put(request, new PendingCacheEntry(request, asyncRlsCall(request)));
    }

    @Override
    public boolean isExpired() {
      return isExpired(ticker.nowInMillis());
    }

    boolean isExpired(long now) {
      return expireTime <= now;
    }

    @Override
    public boolean isStaled(long now) {
      return staleTime <= now;
    }
  }

  /**
   *
   */
  public final class BackoffCacheEntry extends CacheEntry {

    private final Status status;
    private final AtomicBackoff backoff;
    private final ScheduledFuture<?> scheduledFuture;
    private final long backoffExpirationTime;

    private BackoffCacheEntry(
        RouteLookupRequest request,
        Status status,
        AtomicBackoff backoff,
        long backoffExpirationTime) {
      super(request);
      this.status = checkNotNull(status, "status");
      this.backoff = checkNotNull(backoff, "backoff");
      State backoffState = backoff.getState();
      this.scheduledFuture = scheduledExecutorService.schedule(
          new Runnable() {
            @Override
            public void run() {
              transitionToPending();
            }
          },
          Math.min(backoffState.get(), backoffExpirationTime),
          TimeUnit.MILLISECONDS);
      this.backoffExpirationTime = backoffExpirationTime;
    }

    private void transitionToPending() {
      if (!scheduledFuture.isCancelled()) {
        // transition To pending
        pendingCallCache.put(
            request,
            new PendingCacheEntry(request, asyncRlsCall(request), backoff, backoffExpirationTime));
      }
      linkedHashLruCache.invalidate(request);
    }

    public Status getStatus() {
      return status;
    }

    @Override
    public boolean hasData() {
      return false;
    }

    @Override
    public int getSizeBytes() {
      return 0;
    }

    @Override
    boolean isExpired(long now) {
      return backoffExpirationTime <= now;
    }

    @Override
    public boolean isStaled(long now) {
      return isExpired(now);
    }

    @Override
    void cleanup() {
      if (!scheduledFuture.isCancelled()) {
        scheduledFuture.cancel(true);
      }
    }
  }

  private static final class AutoCleaningEvictionListener
      implements EvictionListener<RouteLookupRequest, CacheEntry> {

    private final EvictionListener<RouteLookupRequest, CacheEntry> delegate;

    AutoCleaningEvictionListener(
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onEviction(RouteLookupRequest key, CacheEntry value, EvictionType cause) {
      value.cleanup();

      if (delegate != null) {
        delegate.onEviction(key, value, cause);
      }
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
}
