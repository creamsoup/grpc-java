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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Converter;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Status;
import io.grpc.internal.AtomicBackoff;
import io.grpc.internal.ObjectPool;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.AdaptiveThrottler.SystemTicker;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import io.grpc.rls.RlsPicker.RlsSubchannelStateListener;
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
  // anything on the fly will be here.
  private final Map<RouteLookupRequest, PendingCacheEntry> pendingCallCache;
  private final Executor executor;
  private final Ticker ticker;
  private final Throttler throttler;

  private final long maxAgeMillis;
  private final long staleAgeMillis;
  private final long callTimeoutMillis;
  private final long maxSizeBytes;
  private final long initialBackoffTimeMillis;
  private final long backoffExpirationTimeMillis;
  private final Helper helper;
  private final LbPolicyConfiguration lbPolicyConfig;
  private final Subchannel subchannel;

  @Nullable
  private RouteLookupServiceStub stub;
  private RlsSubchannelStateListener rlsSubchannelStateListener;
  private RlsSubchannelStateListener oobChannelStateListener;

  // TODO: possibly have a sorted map of expire entry to proactively cleanup.

  // TODO: use builder (possibly make a copy constructor as well for
  AsyncCachingRlsClient(Builder builder) {
    this.scheduledExecutorService =
        checkNotNull(builder.scheduledExecutorService, "scheduledExecutorService");
    this.executor = checkNotNull(builder.executor, "executor");
    checkState(builder.maxAgeMillis > 0, "maxAgeMillis should be positive");
    checkState(builder.staleAgeMillis > 0, "staleAgeMillis should be positive");
    checkState(
        builder.maxAgeMillis >= builder.staleAgeMillis,
        "maxAgeMillis should be greater than equals to staleAgeMillis");
    checkState(builder.callTimeoutMillis > 0, "callTimeoutMillis should be positive");
    this.maxAgeMillis = builder.maxAgeMillis;
    this.staleAgeMillis = builder.staleAgeMillis;
    this.callTimeoutMillis = builder.callTimeoutMillis;
    this.initialBackoffTimeMillis = builder.initialBackoffTimeMillis;
    this.backoffExpirationTimeMillis = builder.backoffExpirationTimeMillis;
    this.ticker = checkNotNull(builder.ticker, "ticker");
    this.maxSizeBytes = (int) builder.maxCacheSizeBytes;
    this.throttler = checkNotNull(builder.throttler, "throttler");
    this.linkedHashLruCache =  new LinkedHashLruCache<RouteLookupRequest, CacheEntry>(
        maxSizeBytes,
        new AutoCleaningEvictionListener(builder.evictionListener),
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
    this.helper = checkNotNull(builder.helper, "helper");
    this.lbPolicyConfig = checkNotNull(builder.lbPolicyConfig, "lbPolicyConfig");
    this.subchannel = checkNotNull(builder.subchannel, "subchannel");
    builder.subchannel.start(new SubchannelStateListener() {
      @Override
      public void onSubchannelState(ConnectivityStateInfo newState) {
        // TODO(creamsoup) somehow report OOB channel
        System.out.println("Update OOB subchannel: " + newState.getState());
        handleRlsServerChannelStatus(newState.getState());
        if (oobChannelStateListener != null) {
          oobChannelStateListener.onSubchannelStateChange("oobChannel", newState.getState());
        }
      }
    });
    subchannel.requestConnection();
  }

  /** Returns a Builder for {@link AsyncCachingRlsClient}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  private ConnectivityState oobChannelState = ConnectivityState.IDLE;

  private void handleRlsServerChannelStatus(ConnectivityState state) {
    ConnectivityState oobChannelStateCopy = oobChannelState;
    oobChannelState = state;
    switch (state) {
      case IDLE:
        // fall-through
      case CONNECTING:
        this.stub = null;
        return;
      case READY:
        this.stub = RouteLookupServiceGrpc.newStub(subchannel.asChannel());
        System.out.println("created a stub");
        if (oobChannelStateCopy == ConnectivityState.TRANSIENT_FAILURE) {
          for (CacheEntry cacheEntry : this.linkedHashLruCache.values()) {
            if (cacheEntry instanceof BackoffCacheEntry) {
              ((BackoffCacheEntry) cacheEntry).forceRefresh();
            }
          }
        }
        return;
      case TRANSIENT_FAILURE:
        System.out.println("channel failing");
        return;
      case SHUTDOWN:
        this.stub = null;
    }
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
    System.out.println("calling " + rlsRequest);
    stub.withDeadlineAfter(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .routeLookup(rlsRequest, new StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>() {
          @Override
          public void onNext(io.grpc.lookup.v1.RouteLookupResponse value) {
            response.set(respConverter.reverse().convert(value));
          }

          @Override
          public void onError(Throwable t) {
            System.out.println("on ERROR in asyncCall");
            t.printStackTrace();
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
        System.out.println("pending!");
        return handleNewRequest(request);
      }

      long now = ticker.nowInMillis();
      if (cacheEntry.hasData()) {
        // cache hit, check if entry is staled to fire async-refresh
        DataCacheEntry dataEntry = ((DataCacheEntry) cacheEntry);
        if (dataEntry.isStaled(now)) {
          System.out.println("refresh!");
          dataEntry.maybeRefresh();
        }
      } else {
        System.out.println("cache error backoff!");
        return CachedResponse.backoffEntry((BackoffCacheEntry) cacheEntry);
      }
      System.out.println("cache hit! " + cacheEntry);
      return CachedResponse.dataEntry((DataCacheEntry) cacheEntry);
    }
  }

  /** Performs any pending maintenance operations needed by the cache. */
  public synchronized void close() {
    linkedHashLruCache.close();
  }

  // should hold lock
  private CachedResponse handleNewRequest(RouteLookupRequest request) {
    // all the put is though this method, perform clean up
    PendingCacheEntry pendingEntry = pendingCallCache.get(request);
    if (pendingEntry != null) {
      return CachedResponse.pendingResponse(pendingEntry);
    }

    ListenableFuture<RouteLookupResponse> call = asyncRlsCall(request);
    if (!call.isDone()) {
      pendingEntry = new PendingCacheEntry(request, call);
      this.pendingCallCache.put(request, pendingEntry);
      return CachedResponse.pendingResponse(pendingEntry);
    } else {
      try {
        RouteLookupResponse response = call.get();
        return CachedResponse.dataEntry(new DataCacheEntry(request, response));
      } catch (Exception e) {
        return CachedResponse.backoffEntry(
            new BackoffCacheEntry(
                request,
                Status.fromThrowable(e),
                new AtomicBackoff("rls:" + request.getServer(), initialBackoffTimeMillis)));
      }
    }
  }

  public void addSubchannelStateListener(RlsSubchannelStateListener rlsSubchannelStateListener) {
    this.rlsSubchannelStateListener =
        checkNotNull(rlsSubchannelStateListener, "rlsSubchannelStateListener");
  }

  public void addOobChannelStateListener(RlsSubchannelStateListener oobChannelStateListener) {
    oobChannelStateListener.onSubchannelStateChange("oobChannel", oobChannelState);
    this.oobChannelStateListener = checkNotNull(oobChannelStateListener, "oobChannelStateListener");
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

  /**
   *
   */
  private final class PendingCacheEntry {
    private final ListenableFuture<RouteLookupResponse> pendingCall;
    private final RouteLookupRequest request;
    private final AtomicBackoff backoff;

    PendingCacheEntry(
        RouteLookupRequest request, ListenableFuture<RouteLookupResponse> pendingCall) {
      this(request, pendingCall, null);
    }

    PendingCacheEntry(
        RouteLookupRequest request,
        ListenableFuture<RouteLookupResponse> pendingCall,
        @Nullable AtomicBackoff.State backoffState) {
      System.out.println("new pending entry for " + request);
      this.request = checkNotNull(request, "request");
      this.pendingCall = pendingCall;
      if (backoffState == null) {
        this.backoff = new AtomicBackoff("", initialBackoffTimeMillis);
      } else {
        this.backoff = new AtomicBackoff("", backoffState.get());
      }
      pendingCall.addListener(new Runnable() {
        @Override
        public void run() {
          handleDoneFuture();
        }
      }, executor);
    }

    private void handleDoneFuture() {
      System.out.println("pending future is done for " + request);
      if (pendingCall.isCancelled()) {
        return;
      }

      synchronized (lock) {
        try {
          transitionToData(pendingCall.get());
          System.out.println("to data " + request);
        } catch (Exception e) {
          if (e instanceof ThrottledException) {
            System.out.println("throttled " + request );
            transitionToBackOff(Status.RESOURCE_EXHAUSTED.withCause(e));
          } else {
            System.out.println("error " + request + " " + e.getMessage());
            e.printStackTrace();
            transitionToBackOff(Status.fromThrowable(e));
          }
        }
      }
    }

    @VisibleForTesting
    void transitionToData(RouteLookupResponse routeLookupResponse) {
      pendingCallCache.remove(request);
      linkedHashLruCache.cache(request, new DataCacheEntry(request, routeLookupResponse));
    }

    @VisibleForTesting
    void transitionToBackOff(Status status) {
      pendingCallCache.remove(request);
      linkedHashLruCache.cache(request, new BackoffCacheEntry(request, status, backoff));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("pendingCall", pendingCall)
          .add("request", request)
          .add("backoff", backoff)
          .toString();
    }
  }

  /**
   *
   */
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

  /**
   *
   */
  @VisibleForTesting
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
      System.out.println("data entry request: " + request
          + " response: " + response
          + " childPolicy: " + childPolicyWrapper
          + " server: " + request.getServer());

      if (childPolicyWrapper.getSubchannel() == null) {
        // set picker etc
        childPolicyWrapper.setChildPolicy(lbPolicyConfig.getLoadBalancingPolicy());
        final Subchannel subchannel = helper.createSubchannel(
            CreateSubchannelArgs.newBuilder()
                .setAddresses(RlsUtil.createEag(response.getTarget()))
                .build());
        childPolicyWrapper.setSubchannel(subchannel);
        System.out.println(
            "subchannel created response: " + response + "  created subchannel: " + subchannel);
        subchannel.start(new SubchannelStateListener() {
          @Override
          public void onSubchannelState(ConnectivityStateInfo newState) {
            System.out.println("backend subchannel state changed: " + newState);
            childPolicyWrapper.setConnectivityState(newState.getState());
            if (rlsSubchannelStateListener != null) {
              rlsSubchannelStateListener
                  .onSubchannelStateChange(response.getTarget(), newState.getState());
            }
          }
        });
        subchannel.requestConnection();
      } else {
        System.out.println("woohoo reusing child policy! " + childPolicyWrapper);
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
      System.out.println("data entry expired: " + request);
      if (childPolicyWrapper != null) {
        childPolicyWrapper.release();
      }
    }

    /**
     * Refreshes cache entry, and replacing it immediately. Replaced cache entry will serve the
     * staled value until old cache is expired or new value is available whichever happens first.
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
        System.out.println("maybe refresh the entry " + request);
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
   *
   */
  private final class BackoffCacheEntry extends CacheEntry {

    private final Status status;
    private final AtomicBackoff.State backoffState;
    private final ScheduledFuture<?> scheduledFuture;
    private volatile boolean shutdown = false;

    BackoffCacheEntry(RouteLookupRequest request, Status status, AtomicBackoff backoff) {
      super(request);
      this.status = checkNotNull(status, "status");
      backoffState = checkNotNull(backoff, "backoff").getState();
      backoffState.backoff();
      this.scheduledFuture = scheduledExecutorService.schedule(
          new Runnable() {
            @Override
            public void run() {
              transitionToPending();
            }
          },
          Math.min(backoffState.get(), backoffExpirationTimeMillis),
          TimeUnit.MILLISECONDS);
      System.out.println("Error entry: " + request
          + " status: " + status
          + " backoff time: " + backoffState.get());
    }

    public void forceRefresh() {
      boolean cancelled = scheduledFuture.cancel(false);
      if (cancelled) {
        transitionToPending();
      }
    }

    void transitionToPending() {
      if (shutdown) {
        return;
      }

      synchronized (lock) {
        ListenableFuture<RouteLookupResponse> call = asyncRlsCall(request);
        if (!call.isDone()) {
          System.out.println("transition to pending " + request);
          PendingCacheEntry pendingEntry = new PendingCacheEntry(request, call, backoffState);
          pendingCallCache.put(request, pendingEntry);
          linkedHashLruCache.invalidate(request);
        } else {
          try {
            System.out.println("transition to data from backoff " + request);
            RouteLookupResponse response = call.get();
            linkedHashLruCache.cache(request, new DataCacheEntry(request, response));
          } catch (Exception e) {
            System.out.println("transition to backoff " + request);
            AtomicBackoff backoff = new AtomicBackoff("backoff for " + request, backoffState.get());
            linkedHashLruCache.cache(
                request,
                new BackoffCacheEntry(request, Status.fromThrowable(e), backoff));
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
      return backoffExpirationTimeMillis <= now;
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
          .add("backoffState", backoffState)
          .add("scheduledFuture", scheduledFuture)
          .toString();
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

  /** A Builder for {@link AsyncCachingRlsClient}. */
  public static final class Builder {

    private Helper helper;
    private LbPolicyConfiguration lbPolicyConfig;
    private ScheduledExecutorService scheduledExecutorService;
    private Executor executor;
    private long maxCacheSizeBytes = 100 * 1024 * 1024; // 100 MB
    private long maxAgeMillis = TimeUnit.MINUTES.toMillis(5);
    private long staleAgeMillis = TimeUnit.MINUTES.toMillis(3);
    private long callTimeoutMillis = TimeUnit.SECONDS.toMillis(5);
    private long initialBackoffTimeMillis = TimeUnit.SECONDS.toMillis(1);
    private long backoffExpirationTimeMillis = TimeUnit.SECONDS.toMillis(30);
    private Ticker ticker = new SystemTicker();
    private Throttler throttler = new HappyThrottler();
    private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener = null;
    private Subchannel subchannel;

    public Builder setScheduledExecutorService(
        ScheduledExecutorService scheduledExecutorService) {
      this.scheduledExecutorService =
          checkNotNull(scheduledExecutorService, "scheduledExecutorService");
      return this;
    }

    public Builder setExecutor(Executor executor) {
      this.executor = checkNotNull(executor, "executor");
      return this;
    }

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

    public Builder setInitialBackoffTimeMillis(long initialBackoffTimeMillis) {
      this.initialBackoffTimeMillis = initialBackoffTimeMillis;
      return this;
    }

    public Builder setBackoffExpirationTimeMillis(long backoffExpirationTimeMillis) {
      this.backoffExpirationTimeMillis = backoffExpirationTimeMillis;
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

    public Builder setSubchannel(Subchannel rlsServerChannel) {
      this.subchannel = checkNotNull(rlsServerChannel, "rlsServerChannel");
      return this;
    }

    public AsyncCachingRlsClient build() {
      return new AsyncCachingRlsClient(this);
    }
  }
}
