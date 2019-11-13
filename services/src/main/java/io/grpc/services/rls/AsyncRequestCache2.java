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

package io.grpc.services.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.services.rls.AdaptiveThrottler.SystemTicker;
import io.grpc.services.rls.AdaptiveThrottler.Ticker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An AsyncRequestCache is a cache for expensive RPC call. All the methods in this class are non
 * blocking. This async behavior is reflected to the {@link #get(Object)} method, when the cache
 * is requested but not fully populated, it returns uncompleted {@link ListenableFuture} which
 * allows the users to wait or ignore until the computation is completed.
 *
 * <p>On top of regular cache behavior, it supports max age and stale state of cache value. The key
 * will be expired when max age is reached. When the cached entry is staled (age of cache is in
 * between staled and max), AsyncRequestCache will asynchronously refresh the entry.
 */
@ThreadSafe
abstract class AsyncRequestCache2<K, V> {

  private final LinkedHashMap<K, CacheEntry> cache = new LinkedHashMap<K, CacheEntry>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, CacheEntry> eldest) {
      removalListener.onRemoval(RemovalNotification.create(eldest.getKey(), eldest.getValue().getValue(), RemovalCause.SIZE));
      return size() > maxSize;
    }
  };
  private final Executor executor;
  private final Ticker ticker;

  private final long maxAgeMillis;
  private final long staleAgeMillis;
  private final long callTimeoutMillis;
  private final int maxSize;
  private final RemovalListener<K, ListenableFuture<V>> removalListener;

  AsyncRequestCache2(
      Executor executor,
      long maxAgeMillis,
      long staleAgeMillis,
      long maxCacheSize,
      long callTimeoutMillis) {
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
    ticker = new SystemTicker();
    this.maxSize = (int) maxCacheSize;
    this.removalListener = null;
    // cache = CacheBuilder.newBuilder()
    //     .maximumSize(maxCacheSize)
    //     .expireAfterWrite(maxAgeMillis + callTimeoutMillis, TimeUnit.MILLISECONDS)
    //     .build();
  }

  @VisibleForTesting
  @SuppressWarnings("BetaApi") // Test only.
  AsyncRequestCache2(
      Executor executor,
      long maxAgeMillis,
      long staleAgeMillis,
      long maxCacheSize,
      long callTimeoutMillis,
      Ticker ticker,
      final RemovalListener<K, ListenableFuture<V>> removalListener) {
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
    checkNotNull(removalListener, "removalListener");
    this.maxSize = (int) maxCacheSize;
    this.removalListener = removalListener;
    // cache = CacheBuilder.newBuilder()
    //     .maximumSize(maxCacheSize)
    //     // .concurrencyLevel(Runtime.getRuntime().availableProcessors())
    //     .expireAfterWrite(maxAgeMillis + callTimeoutMillis, TimeUnit.MILLISECONDS)
    //     .ticker(new com.google.common.base.Ticker() {
    //       @Override
    //       public long read() {
    //         return TimeUnit.MILLISECONDS.toNanos(AsyncRequestCache2.this.ticker.nowInMillis());
    //       }
    //     })
    //     .removalListener(new RemovalListener<K, CacheEntry>() {
    //       @Override
    //       public void onRemoval(RemovalNotification<K, CacheEntry> notification) {
    //         if (notification.wasEvicted()) {
    //           removalListener.onRemoval(
    //               RemovalNotification.create(
    //                   notification.getKey(),
    //                   notification.getValue().getValue(),
    //                   notification.getCause()));
    //         }
    //       }
    //     })
    //     .build();
  }

  /** Performs an async RPC call if cached value doesn't exists. */
  @CheckReturnValue
  protected abstract ListenableFuture<V> rpcCall(K key);

  /**
   * Returns the value associated with {@code key} in this cache, obtaining that value from {@code
   * loader} if necessary. The method improves upon the conventional "if cached, return; otherwise
   * create, cache and return" pattern. If the cache was not present, it returns a future value.
   * Callers may wait for the future if necessary.
   */
  @CheckReturnValue
  public final synchronized ListenableFuture<V> get(final K key) {
    final CacheEntry cacheEntry;
    cacheEntry = cache.get(key);
    if (cacheEntry == null) {
      return populateCache(key).getValue();
    }

    if (cacheEntry.status == CallStatus.PENDING) {
      return cacheEntry.getValue();
    }

    long now = ticker.nowInMillis();
    if (cacheEntry.expireTime <= now) {
      // Cache is expired
      if (!cacheEntry.refreshInitiated) {
        return populateCache(key).getValue();
      }
      // Impossible, because refresh is replacing the old CacheEntry
      throw new AssertionError("This is a bug, please report an issue.");
    } else if (cacheEntry.staleTime <= now) {
      // current entry is staled, but not expired yet.
      if (!cacheEntry.refreshInitiated) {
        refresh(key, cacheEntry);
      }
      return cacheEntry.getValue();
    }
    // cache hit!
    return cacheEntry.getValue();
  }

  /** Performs any pending maintenance operations needed by the cache. */
  public synchronized void cleanUp() {
  }

  private CacheEntry populateCache(K key) {
    return populateCache(key, null);
  }

  private CacheEntry populateCache(K key, @Nullable CacheEntry staledEntry) {
    // all the put is though this method, perform clean up
    final ListenableFuture<V> future = rpcCall(key);
    final CacheEntry cacheEntry = new CacheEntry(key, future, staledEntry);
    future.addListener(
        new Runnable() {
          @Override
          public void run() {
            if (future.isCancelled()) {
              return;
            }

            try {
              V unused = future.get();
              // update cache's internal states when call is successfully finished
              long nowInMillis = ticker.nowInMillis();
              cacheEntry.expireTime = nowInMillis + maxAgeMillis;
              cacheEntry.staleTime = nowInMillis + staleAgeMillis;
              cacheEntry.status = CallStatus.FINISHED;
            } catch (Exception e) {
              // no-op
            }
          }
        },
        executor);
    cache.put(key, cacheEntry);
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
  private void refresh(final K key, CacheEntry oldValue) {
    if (oldValue.refreshInitiated) {
      return;
    }
    oldValue.refreshInitiated = true;
    populateCache(key, oldValue);
  }

  /**
   * A CallStatus indicates the status of RPC call. It doesn't necessarily indicate the cache's
   * status.
   */
  private enum CallStatus {
    /**
     * Current call is finished successfully, the cache entry can be in STALED or EXPIRED status.
     */
    FINISHED,
    /**
     * Call is still on the fly, but it may currently serving staled value if it was replacing
     * staled entry.
     */
    PENDING
  }

  private final class CacheEntry {
    final K key;
    final ListenableFuture<V> value;
    boolean refreshInitiated = false;

    long expireTime;
    long staleTime;
    CallStatus status = CallStatus.PENDING;

    @Nullable
    final ListenableFuture<V> staledValue;
    final long staledValueExpireTime;


    CacheEntry(K key, ListenableFuture<V> value, @Nullable CacheEntry staledEntry) {
      this.key = checkNotNull(key, "key");
      this.value = checkNotNull(value, "value");
      this.staledValue = staledEntry != null ? staledEntry.value : null;
      this.staledValueExpireTime = staledEntry != null ? staledEntry.expireTime : 0L;
      // TTLs will be updated to actual value when the call is succeeded
      expireTime = ticker.nowInMillis() + callTimeoutMillis;
      staleTime = expireTime;
    }

    /**
     * Returns current value of cache entry. If the current entry is in the middle of replacing the
     * old available cache entry, it can still returns previous value until previous value is
     * expired or new value is available.
     */
    public ListenableFuture<V> getValue() {
      if (!value.isDone()
          && !value.isCancelled()
          && staledValue != null
          && staledValueExpireTime < ticker.nowInMillis()) {
        return staledValue;
      }
      return value;
    }
  }
}
