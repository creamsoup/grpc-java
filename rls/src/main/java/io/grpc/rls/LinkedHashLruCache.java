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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A {@link LinkedHashLruCache} implements least recently used caching where it supports access
 * order lru cache eviction while allowing entry level expiration time. When the cache reaches max
 * capacity, LruCache try to remove up to one already expired entries. If it doesn't find any
 * expired entries, it will remove based on access order of entry. On top of this, LruCache also
 * proactively removed expired entries based on configured time interval.
 */
// TODO(creamsoup)
//  - consider making it concurrent data structure
//  - when max size reached, should it clean all? because it is still o(n)
@ThreadSafe
abstract class LinkedHashLruCache<K, V> implements LruCache<K, V> {

  private final Object lock = new Object();

  @GuardedBy("lock")
  private final LinkedHashMap<K, SizedValue> delegate;
  private final PeriodicCleaner periodicCleaner;
  private final Ticker ticker;
  private final EvictionListener<K, SizedValue> evictionListener;
  private final AtomicLong estimatedSizeBytes = new AtomicLong();
  private long estimatedMaxSizeBytes;

  LinkedHashLruCache(
      final long maxEstimatedSizeBytes,
      @Nullable final EvictionListener<K, V> evictionListener,
      int cleaningInterval,
      TimeUnit cleaningIntervalUnit,
      ScheduledExecutorService ses,
      final Ticker ticker) {
    checkState(maxEstimatedSizeBytes > 0, "max estimated cache size should be positive");
    this.estimatedMaxSizeBytes = maxEstimatedSizeBytes;
    this.evictionListener = new SizeHandlingEvictionListener(evictionListener);
    this.ticker = checkNotNull(ticker, "ticker");
    delegate = new LinkedHashMap<K, SizedValue>(
        // rough estimate or minimum hashmap default
        Math.max((int) (maxEstimatedSizeBytes / 1000), 16),
        /* loadFactor= */ 0.75f,
        /* accessOrder= */ true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, SizedValue> eldest) {
        if (estimatedSizeBytes.get() <= LinkedHashLruCache.this.estimatedMaxSizeBytes) {
          return false;
        }

        // first, remove at most 1 expired entry
        boolean removed = cleanupExpiredEntries(1, ticker.nowInMillis());
        // handles size based eviction if necessary no expired entry
        boolean shouldRemove =
            !removed && shouldInvalidateEldestEntry(eldest.getKey(), eldest.getValue().value);
        if (shouldRemove) {
          // remove entry by us to make sure lruIterator and cache is in sync
          LinkedHashLruCache.this.invalidate(eldest.getKey(), EvictionType.SIZE);
        }
        return false;
      }
    };
    checkNotNull(ses, "ses");
    checkState(cleaningInterval > 0, "cleaning interval must be positive");
    checkNotNull(cleaningIntervalUnit, "cleaningIntervalUnit");
    periodicCleaner = new PeriodicCleaner(ses, cleaningInterval, cleaningIntervalUnit).start();
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

  /**
   * Returns estimated size of entry to keep track. If it always returns 1, the max size bytes
   * behaves like max number of entry (default behavior).
   */
  protected int estimateSizeOf(K key, V value) {
    return 1;
  }

  /** Updates size for given key if entry exists. It is useful if the cache value is mutated. */
  final void updateEntrySize(K key) {
    SizedValue entry = readInternal(key);
    if (entry == null) {
      return;
    }
    int prevSize = entry.size;
    int newSize = estimateSizeOf(key, entry.value);
    entry.size = newSize;
    estimatedSizeBytes.addAndGet(newSize - prevSize);
  }

  @Override
  @Nullable
  public final V cache(K key, V value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    SizedValue existing;
    int size = estimateSizeOf(key, value);
    synchronized (lock) {
      estimatedSizeBytes.addAndGet(size);
      existing = delegate.put(key, new SizedValue(size, value));
      if (existing != null) {
        evictionListener.onEviction(key, existing, EvictionType.REPLACED);
      }
    }
    return existing == null ? null : existing.value;
  }

  @Override
  @Nullable
  @CheckReturnValue
  public final V read(K key) {
    SizedValue entry = readInternal(key);
    if (entry != null) {
      return entry.value;
    }
    return null;
  }

  @Nullable
  @CheckReturnValue
  private SizedValue readInternal(K key) {
    checkNotNull(key, "key");
    synchronized (lock) {
      SizedValue existing = delegate.get(key);
      if (existing != null && isExpired(key, existing.value, ticker.nowInMillis())) {
        invalidate(key, EvictionType.EXPIRED);
        return null;
      }
      return existing;
    }
  }

  @Override
  @Nullable
  public final V invalidate(K key) {
    return invalidate(key, EvictionType.EXPLICIT);
  }

  @Override
  @Nullable
  public final V invalidate(K key, EvictionType cause) {
    checkNotNull(key, "key");
    checkNotNull(cause, "cause");
    synchronized (lock) {
      SizedValue existing = delegate.remove(key);
      if (existing != null) {
        evictionListener.onEviction(key, existing, cause);
      }
      return existing == null ? null : existing.value;
    }
  }

  @Override
  public final void invalidateAll(Iterable<K> keys) {
    invalidateAll(keys, EvictionType.EXPLICIT);
  }

  @Override
  public final void invalidateAll(Iterable<K> keys, EvictionType cause) {
    checkNotNull(keys, "keys");
    checkNotNull(cause, "cause");
    synchronized (lock) {
      for (K key : keys) {
        SizedValue existing = delegate.remove(key);
        if (existing != null) {
          evictionListener.onEviction(key, existing, cause);
        }
      }
    }
  }

  @Override
  @CheckReturnValue
  public final boolean hasCacheEntry(K key) {
    // call get to handle expired
    return readInternal(key) != null;
  }

  /** Returns shallow copied values in the cache. */
  public final List<V> values() {
    synchronized (lock) {
      List<V> list = new ArrayList<>(delegate.size());
      for (SizedValue value : delegate.values()) {
        list.add(value.value);
      }
      return Collections.unmodifiableList(list);
    }
  }

  /**
   * Resizes cache. If new size is smaller than current estimated size, it will free up space by
   * removing expired entries and removing oldest entries by LRU order.
   */
  public final void resize(int newSizeBytes) {
    long now = ticker.nowInMillis();
    synchronized (lock) {
      long estimatedSizeBytesCopy = estimatedMaxSizeBytes;
      this.estimatedMaxSizeBytes = newSizeBytes;
      if (estimatedSizeBytesCopy <= newSizeBytes) {
        // new size is larger no need to do cleanup
        return;
      }
      // cleanup expired entries
      cleanupExpiredEntries(now);

      // cleanup eldest entry until new size limit
      Iterator<Map.Entry<K, SizedValue>> lruIter = delegate.entrySet().iterator();
      while (lruIter.hasNext() && estimatedMaxSizeBytes > this.estimatedSizeBytes.get()) {
        Map.Entry<K, SizedValue> entry = lruIter.next();
        lruIter.remove();
        // eviction listener will update the estimatedSizeBytes
        evictionListener.onEviction(entry.getKey(), entry.getValue(), EvictionType.SIZE);
      }
    }
  }

  @Override
  @CheckReturnValue
  public final int estimatedSize() {
    synchronized (lock) {
      return delegate.size();
    }
  }

  private boolean cleanupExpiredEntries(long now) {
    return cleanupExpiredEntries(Integer.MAX_VALUE, now);
  }

  // maxExpiredEntries is by number of entries
  private boolean cleanupExpiredEntries(int maxExpiredEntries, long now) {
    checkArgument(maxExpiredEntries > 0, "maxExpiredEntries must be positive");
    boolean removedAny = false;
    synchronized (lock) {
      Iterator<Map.Entry<K, SizedValue>> lruIter = delegate.entrySet().iterator();
      while (lruIter.hasNext() && maxExpiredEntries > 0) {
        Map.Entry<K, SizedValue> entry = lruIter.next();
        if (isExpired(entry.getKey(), entry.getValue().value, now)) {
          lruIter.remove();
          evictionListener.onEviction(entry.getKey(), entry.getValue(), EvictionType.EXPIRED);
          removedAny = true;
          maxExpiredEntries--;
        }
      }
    }
    return removedAny;
  }

  @Override
  public final void close() {
    //TODO maybe clear map/set?
    periodicCleaner.stop();
    doClose();
  }

  protected void doClose() {}

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

  /** A {@link EvictionListener} keeps track of size. */
  private final class SizeHandlingEvictionListener implements EvictionListener<K, SizedValue> {

    private final EvictionListener<K, V> delegate;

    SizeHandlingEvictionListener(@Nullable EvictionListener<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onEviction(K key, SizedValue value, EvictionType cause) {
      estimatedSizeBytes.addAndGet(-1 * estimateSizeOf(key, value.value));
      if (delegate != null) {
        delegate.onEviction(key, value.value, cause);
      }
    }
  }

  private final class SizedValue {
    volatile int size;
    final V value;

    SizedValue(int size, V value) {
      this.size = size;
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      // NOTE: the size doesn't affect equality
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      @SuppressWarnings("unchecked")
      SizedValue that = (SizedValue) o;
      return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      // NOTE: the size doesn't affect hashCode
      return Objects.hash(value);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("size", size)
          .add("value", value)
          .toString();
    }
  }
}
