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

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.LoadBalancer.Helper;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import io.grpc.rls.LinkedHashLruCache.EvictionListener;
import io.grpc.rls.LinkedHashLruCache.EvictionType;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An AsyncRlsCache is a cache for rls service. All the methods in this class are non
 * blocking. This async behavior is reflected to the {@link #get(Object, Helper)} method, when the
 * cache is requested but not fully populated, it returns uncompleted {@link ListenableFuture} which
 * allows the users to wait or ignore until the computation is completed.
 *
 * <p>On top of regular cache behavior, it supports max age and stale state of cache value. The key
 * will be expired when max age is reached. When the cached entry is staled (age of cache is in
 * between staled and max), AsyncRequestCache will asynchronously refresh the entry.
 */
@ThreadSafe
abstract class AsyncRlsCache<K, V extends ListenableFuture> {

  private final ScheduledExecutorService scheduledExecutorService;
  // LRU cache based on access order
  private final LinkedHashLruCache<K, CacheEntry> linkedHashLruCache;
  private final Executor executor;
  private final Ticker ticker;

  private final long maxAgeMillis;
  private final long staleAgeMillis;
  private final long callTimeoutMillis;
  private final int maxSizeBytes;

  // TODO: possibly have a sorted map of expire entry to proactively cleanup.

  AsyncRlsCache(
      ScheduledExecutorService scheduledExecutorService,
      Executor executor,
      long maxAgeMillis,
      long staleAgeMillis,
      long maxCacheSize,
      long callTimeoutMillis,
      Ticker ticker,
      EvictionListener<K, V> evictionListener) {
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
    this.linkedHashLruCache =  new LinkedHashLruCache<K, CacheEntry>(
        maxSizeBytes,
        new CancelingEvictionListener(evictionListener),
        1,
        TimeUnit.MINUTES,
        scheduledExecutorService,
        ticker) {

      @Override
      protected boolean isExpired(K key, CacheEntry value, long nowInMillis) {
        // TODO what about refresh?
        return value.getStatus() == CallStatus.SUCCEEDED && value.expireTime <= nowInMillis;
      }

      @Override
      protected int estimateSizeOf(K key, CacheEntry value) {
        return AsyncRlsCache.this.estimateSizeOf(key, value.getValue());
      }
    };
  }

  /** Returns estimated size of an entry. */
  protected abstract int estimateSizeOf(K key, V value);

  /** Performs an async RPC call if cached value doesn't exists. */
  @CheckReturnValue
  protected abstract V rpcCall(K key, Helper helper);

  /**
   * Returns the value associated with {@code key} in this cache, obtaining that value from {@code
   * loader} if necessary. The method improves upon the conventional "if cached, return; otherwise
   * create, cache and return" pattern. If the cache was not present, it returns a future value.
   * Callers may wait for the future if necessary.
   */
  @CheckReturnValue
  public final synchronized V get(final K key, Helper helper) {
    final CacheEntry cacheEntry;
    cacheEntry = linkedHashLruCache.read(key);
    if (cacheEntry == null) {
      return populateCache(key, helper).getValue();
    }

    if (cacheEntry.getStatus() == CallStatus.PENDING) {
      return cacheEntry.getValue();
    }

    long now = ticker.nowInMillis();
    // check if entry is staled and fire async-refresh
    if (cacheEntry.staleTime <= now) {
      if (!cacheEntry.hasPendingCall) {
        refresh(key, cacheEntry, helper);
      }
    }
    return cacheEntry.getValue();
  }

  /** Performs any pending maintenance operations needed by the cache. */
  public synchronized void cleanUp() {
    linkedHashLruCache.close();
  }

  private CacheEntry populateCache(K key, Helper helper) {
    return populateCache(key, null, helper);
  }

  private CacheEntry populateCache(final K key, @Nullable CacheEntry staledEntry, Helper helper) {
    // all the put is though this method, perform clean up
    final V future = rpcCall(key, helper);
    final CacheEntry cacheEntry = new CacheEntry(key, future, staledEntry);
    cacheEntry.registerRefreshCall(future);
    linkedHashLruCache.cache(key, cacheEntry);
    return cacheEntry;
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
  private void refresh(final K key, CacheEntry oldValue, Helper helper) {
    if (oldValue.hasPendingCall) {
      return;
    }
    oldValue.hasPendingCall = true;
    populateCache(key, oldValue, helper);
  }

  /**
   * A CallStatus indicates the status of RPC call. It doesn't necessarily indicate the cache's
   * status.
   *
   * <p>Life cycle of states
   *
   * <pre>
   *        created                       __
   *           |                          \v
   *           V
   * (*1)-> PENDING  ----------------> BACKOFF
   *  |        |                          |
   *  |        V                          |
   *  |->  SUCCEEDED   <-------------------
   *  |        |                          |
   *  |        V                          |
   *  ---   staled (SUCCEEDED)            |
   *           |                          |
   *           V                          |
   *        evicted   <--------------------
   * *1) when Refresh is not finished, but the original entry is expired.
   * </pre>
   */
  private enum CallStatus {
    /**  There is no RLS request pending and the most recent RLS request succeeded. */
    NO_REQUEST,
    /**
     * Current call is finished successfully, the cache entry can be in STALED or EXPIRED status.
     */
    SUCCEEDED,
    /**
     * Call is still on the fly, but it may currently serving staled value if it was replacing
     * staled entry.
     */
    PENDING,
    /** Call is failed and we are in exponential backoff pending a potential retry. */
    BACKOFF,
    /**
     * The latest backoff delay has elapsed but we are waiting for a new pick before attempting to
     * retry the RLS request.
     */
    BACKOFF_EXPIRED
  }

  private final class CacheEntry {

    final K key;
    // value is an ListenableFuture, we use listener to control pending call
    final V value;
    boolean hasPendingCall = false;

    long expireTime;
    long staleTime;
    CallStatus status = CallStatus.PENDING;

    @Nullable
    final V staledValue;
    final long staledValueExpireTime;

    // TODO add/handle those
    // BackOff backOffState;
    // backoff timer
    // backoffCallback
    // backoffExpirationTime

    CacheEntry(K key, V value, @Nullable CacheEntry staledEntry) {
      this.key = checkNotNull(key, "key");
      this.value = checkNotNull(value, "value");
      this.staledValue = staledEntry != null ? staledEntry.value : null;
      this.staledValueExpireTime = staledEntry != null ? staledEntry.expireTime : 0L;
      // TTLs will be updated to actual value when the call is succeeded
      expireTime = ticker.nowInMillis() + callTimeoutMillis;
      staleTime = expireTime;
      addRpcFutureCallback();
    }

    private void addRpcFutureCallback() {
      // handle initial rpc completion
      // it will be in pending status
      //    -> SUCCEEDED if nothing bad happens
      //    -> BACKOFF if fails
      // removed will be handled in cleanup
      value.addListener(new Runnable() {
        @Override
        public void run() {
          if (value.isCancelled()) {
            return;
          }

          try {
            Object unused = value.get();
            // update cache's internal states when call is successfully finished
            long nowInMillis = ticker.nowInMillis();
            expireTime = nowInMillis + maxAgeMillis;
            staleTime = nowInMillis + staleAgeMillis;
            setStatus(CallStatus.SUCCEEDED);
          } catch (Exception e) {
            // GO BACKOFF STATUS
            // failed cache shouldn't be cached
            // TODO(creamsoup) how to handle if anything waiting for this result???
          }
        }
      }, executor);
    }

    /**
     * Returns current value of cache entry. If the current entry is in the middle of replacing the
     * old available cache entry, it can still returns previous value until previous value is
     * expired or new value is available.
     */
    V getValue() {
      if (!value.isDone()
          && !value.isCancelled()
          && staledValue != null
          && staledValueExpireTime < ticker.nowInMillis()) {
        return staledValue;
      }
      return value;
    }

    CallStatus getStatus() {
      // TODO handle when pending call and trasition case
      return status;
    }

    void setStatus(CallStatus status) {
      this.status = checkNotNull(status, "status");
    }

    void registerRefreshCall(final V callFuture) {
      // this is handling refresh when the entry is stabled but yet valid and successful
      //  -> it will be still succeeded until the previous entry is expired
      //  -> it may enter PENDING if the previous entry is expired, but pending call is not finished
      //  -> it may enter SUCCEEDED if the refresh call finished before prev expired
      //  -> it may enter BACKOFF if the refresh call is failed and previous entry is expired
      callFuture.addListener(new Runnable() {
        @Override
        public void run() {
          if (callFuture.isCancelled()) {
            // TODO Must check if staled is still valid or vice versa
            linkedHashLruCache.invalidate(key, EvictionType.ERROR);
            return;
          }

          try {
            Object unused = callFuture.get();
            // update cache's internal states when call is successfully finished
            long nowInMillis = ticker.nowInMillis();
            expireTime = nowInMillis + maxAgeMillis;
            staleTime = nowInMillis + staleAgeMillis;
            setStatus(CallStatus.SUCCEEDED);
          } catch (Exception e) {
            // GO BACKOFF STATUS
            // failed cache shouldn't be cached
            // TODO(creamsoup) how to handle if anything waiting for this result???
          }
        }
      }, executor);
    }

    void cleanup() {
      // called when cache is evicted or replaced
      // may need to cancel staled / curr
    }
  }

  private final class CancelingEvictionListener implements EvictionListener<K, CacheEntry> {

    private final EvictionListener<K, V> delegate;

    CancelingEvictionListener(@Nullable EvictionListener<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onEviction(K key, CacheEntry value, EvictionType cause) {
      value.cleanup();

      if (delegate != null) {
        delegate.onEviction(key, value.getValue(), cause);
      }
    }
  }
}
