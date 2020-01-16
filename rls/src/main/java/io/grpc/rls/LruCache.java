/*
 * Copyright 2020 The gRPC Authors
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

import io.grpc.rls.AdaptiveThrottler.Ticker;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A {@link LruCache} implements least recently used caching where it supports access order lru
 * cache eviction while allowing entry level expiration time. When the cache reaches max capacity,
 * LruCache try to remove up to one already expired entries. If it doesn't find any expired entries,
 * it will remove based on access order of entry. On top of this, LruCache also proactively removed
 * expired entries based on configured time interval.
 */
// TODO consider striped lock to increase performance in concurrent env
//  when max size reached, should it clean all? because it is still o(n)
@ThreadSafe
public abstract class LruCache<K, V> {

  private final Object lock = new Object();

  @GuardedBy("lock")
  private final LinkedHashMap<K, V> delegate;
  private final PeriodicCleaner periodicCleaner;
  private final Ticker ticker;
  private final EvictionListener<K, V> evictionListener;
  private final int maxSize;

  public LruCache(
      final int maxSize,
      @Nullable final EvictionListener<K, V> evictionListener,
      int cleaningInterval,
      TimeUnit cleaningIntervalUnit,
      ScheduledExecutorService ses,
      final Ticker ticker) {
    checkState(maxSize > 0, "max cache size should be positive");
    this.maxSize = maxSize;
    this.evictionListener = evictionListener
        == null ? new EmptyEvictionListener() : evictionListener;
    this.ticker = checkNotNull(ticker, "ticker");
    delegate = new LinkedHashMap<K, V>(maxSize, /* loadFactor= */ 0.75f, /* accessOrder= */ true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        if (size() <= maxSize) {
          return false;
        }

        // first, remove at most 1 expired entry
        boolean removed = cleanupExpiredEntries(1, ticker.nowInMillis());
        // handles size based eviction if necessary
        boolean shouldRemove =
            !removed && shouldInvalidateEldestEntry(eldest.getKey(), eldest.getValue());
        if (shouldRemove) {
          // remove entry by us to make sure lruIterator and cache is in sync
          LruCache.this.invalidate(eldest.getKey(), EvictionType.SIZE);
        }
        return false;
      }
    };
    checkNotNull(ses, "ses");
    checkState(cleaningInterval > 0, "cleaning interval must be positive");
    checkNotNull(cleaningIntervalUnit, "cleaningIntervalUnit");
    periodicCleaner = new PeriodicCleaner(ses, cleaningInterval, cleaningIntervalUnit).start();
  }

  /** Populates a cache entry. If the key already exists, it will replace the entry. */
  @Nullable
  public final V cache(K key, V value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    synchronized (lock) {
      V existing = delegate.put(key, value);
      if (existing != null) {
        evictionListener.onEviction(key, existing, EvictionType.REPLACED);
      }
      return existing;
    }
  }

  /**
   * Returns cached value for given key if exists. This operation doesn't return already expired
   * cache entry.
   */
  @Nullable
  @CheckReturnValue
  public final V read(K key) {
    checkNotNull(key, "key");
    synchronized (lock) {
      V existing = delegate.get(key);
      if (existing != null && isExpired(key, existing, ticker.nowInMillis())) {
        invalidate(key, EvictionType.EXPIRED);
        return null;
      }
      return existing;
    }
  }

  /**
   * Invalidates an entry for given key if exists. This operation will trigger {@link
   * EvictionListener} with {@link EvictionType#EXPLICIT}.
   */
  @Nullable
  public final V invalidate(K key) {
    return invalidate(key, EvictionType.EXPLICIT);
  }

  /**
   * Invalidates an entry for given key if exists. This operation will trigger {@link
   * EvictionListener} with given cause.
   */
  @Nullable
  public final V invalidate(K key, EvictionType cause) {
    checkNotNull(key, "key");
    checkNotNull(cause, "cause");
    synchronized (lock) {
      V existing = delegate.remove(key);
      if (existing != null) {
        evictionListener.onEviction(key, existing, cause);
      }
      return existing;
    }
  }

  /**
   * Invalidates cache entries for given keys. This operation will trigger {@link EvictionListener}
   * with {@link EvictionType#EXPLICIT}.
   */
  public final void invalidateAll(Iterable<K> keys) {
    invalidateAll(keys, EvictionType.EXPLICIT);
  }

  /**
   * Invalidates cache entries for given keys. This operation will trigger {@link EvictionListener}
   * with given cause.
   */
  public final void invalidateAll(Iterable<K> keys, EvictionType cause) {
    checkNotNull(keys, "keys");
    checkNotNull(cause, "cause");
    synchronized (lock) {
      for (K key : keys) {
        V existing = delegate.remove(key);
        if (existing != null) {
          evictionListener.onEviction(key, existing, cause);
        }
      }
    }
  }

  /** Returns {@code true} if given key is cached. */
  @CheckReturnValue
  public final boolean hasCacheEntry(K key) {
    // call get to handle expired
    return read(key) != null;
  }

  /**
   * Returns the size of cache. Note that the size can be larger than its true size because there
   * might be already expired cache.
   */
  @CheckReturnValue
  public final int estimatedSize() {
    synchronized (lock) {
      return delegate.size();
    }
  }

  private boolean cleanupExpiredEntries(long now) {
    return cleanupExpiredEntries(0, now);
  }

  private boolean cleanupExpiredEntries(int limit, long now) {
    if (limit == 0) {
      limit = maxSize;
    }
    boolean removedAny = false;
    synchronized (lock) {
      Iterator<Map.Entry<K, V>> lruIter = delegate.entrySet().iterator();
      while (lruIter.hasNext() && limit > 0) {
        Map.Entry<K, V> entry = lruIter.next();
        if (isExpired(entry.getKey(), entry.getValue(), now)) {
          lruIter.remove();
          evictionListener.onEviction(entry.getKey(), entry.getValue(), EvictionType.EXPIRED);
          removedAny = true;
          limit--;
        }
      }
    }
    return removedAny;
  }

  /**
   * Determines if the eldest entry should be kept or not when the cache size limit is reached. Note
   * that LruCache is access level and the eldest is determined by access pattern.
   */
  protected boolean shouldInvalidateEldestEntry(K eldestKey, V eldestValue) {
    return true;
  }

  /** Determines if the entry is already expired or not. */
  protected abstract boolean isExpired(K key, V value, long nowInMillis);

  public final void close() {
    //TODO maybe clear map/set?
    periodicCleaner.stop();
  }

  /** Periodically cleans up the AsyncRequestCache. */
  private final class PeriodicCleaner implements Runnable {

    private final ScheduledExecutorService ses;
    private final int interval;
    private final TimeUnit intervalUnit;
    @Nullable
    private ScheduledFuture<?> scheduledFuture;

    PeriodicCleaner(ScheduledExecutorService ses, int interval, TimeUnit intervalUnit) {
      this.ses = checkNotNull(ses, "ses");
      checkState(interval > 0, "interval must be positive");
      this.interval = interval;
      this.intervalUnit = checkNotNull(intervalUnit, "intervalUnit");
    }

    PeriodicCleaner start() {
      checkState(scheduledFuture == null, "cleaning task can be started only once");
      this.scheduledFuture =
          ses.scheduleAtFixedRate(this, interval, interval, intervalUnit);
      return this;
    }

    void stop() {
      if (scheduledFuture != null) {
        scheduledFuture.cancel(false);
        scheduledFuture = null;
      }
    }

    @Override
    public void run() {
      cleanupExpiredEntries(ticker.nowInMillis());
    }
  }

  /** A listener to notify when a cache entry is evicted. */
  public interface EvictionListener<K, V> {

    /** Notifies the listener when any cache entry is evicted. */
    void onEviction(K key, V value, EvictionType cause);
  }

  /** A {@link EvictionListener} doesn't do anything. */
  private final class EmptyEvictionListener implements EvictionListener<K, V> {

    @Override
    public void onEviction(K key, V value, EvictionType cause) {
      // no-op
    }
  }

  /** An EvictionType indicates the cause of eviction from {@link LruCache}. */
  public enum EvictionType {
    /** Explicitly removed by user. */
    EXPLICIT,
    /** Evicted due to size limit. */
    SIZE,
    /** Evicted due to entry expired. */
    EXPIRED,
    /** Removed due to error. */
    ERROR,
    /** Evicted by replacement. */
    REPLACED
  }
}
