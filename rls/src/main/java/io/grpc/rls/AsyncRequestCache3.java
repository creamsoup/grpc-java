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

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.CheckReturnValue;

abstract class AsyncRequestCache3<K, V> {
  private final int maxSize;
  private final int concurrencyLevel;
  // this will be active segment's TTL
  private final long maxAgeMillis;
  private final long staleAgeMillis;
  // this will be pending segment's TTL
  private final long callTimeoutMillis;
  private final Executor executor;
  private final Ticker ticker;

  // has n * 2 segments
  // [pending_segment_0, active_segment_0, pending_segment_1, active_segment_1, ...]
  // segmentLocks[i] need to be acquired to access *_segment_i
  private final Object[] segmentLocks;
  private final HashMap<K, CacheEntry>[] segments;
  private final RemovalListener<K, ListenableFuture<V>> removalListener;
  private final ConcurrentLinkedQueue<Recency<K>> recencyQueue = new ConcurrentLinkedQueue<>();

  @SuppressWarnings({"unchecked", "rawtypes"})
  AsyncRequestCache3(
      Executor executor,
      long maxAgeMillis,
      long staleAgeMillis,
      long maxCacheSize,
      long callTimeoutMillis,
      Ticker ticker,
      final RemovalListener<K, ListenableFuture<V>> removalListener) {
    this.executor = checkNotNull(executor, "executor");
    this.maxSize = (int) maxCacheSize;
    this.maxAgeMillis = maxAgeMillis;
    this.staleAgeMillis = staleAgeMillis;
    this.callTimeoutMillis = callTimeoutMillis;
    this.concurrencyLevel = 12;
    this.removalListener = removalListener;
    this.segments = (HashMap<K, CacheEntry>[]) new HashMap[concurrencyLevel * 2];
    for (int i = 0; i < concurrencyLevel * 2; i++) {
      int segmentInitialSize = Math.max(16, maxSize / concurrencyLevel);
      segments[i] = new HashMap<>(segmentInitialSize, 0.75f);
    }
    segmentLocks = new Object[concurrencyLevel];
    for (int i = 0; i < concurrencyLevel; i++) {
      segmentLocks[i] = new Object();
    }
    this.ticker = ticker;
  }

  private AtomicLong cnt = new AtomicLong();

  @CheckReturnValue
  public final ListenableFuture<V> get(final K key) {
    Object lock = getLock(key);
    synchronized (lock) {
      try {
        long now = ticker.nowInMillis();
        // check active first to maximize get throughput
        HashMap<K, CacheEntry> activeSegment = getActiveSegment(key);
        HashMap<K, CacheEntry> pendingSegment = getPendingSegment(key);
        CacheEntry entry = activeSegment.get(key);
        if (entry == null) {
          // cache miss
          CacheEntry pending = pendingSegment.get(key);
          if (pending != null) {
            return pending.value;
          }
          // no pending value
          return populateCache(pendingSegment, key).value;
        } else if (entry.isExpired(now)) {
          removeExpiredEntry(activeSegment, entry);
          if (!pendingSegment.containsKey(key)) {
            return populateCache(pendingSegment, key).value;
          }
        } else if (entry.isStaled(now)) {
          // need to check pending
          // add refresh in pending segment. since get is check active first, it will still use
          // active until the staled entry is expired.
          if (!pendingSegment.containsKey(key)) {
            populateCache(pendingSegment, key);
          }
        }
        // cache hit
        return entry.value;
      } finally {
        if (cnt.incrementAndGet() % 64 == 0) {
          removeExpired(5);
        }
      }
    }
  }

  private void removeExpiredEntry(HashMap<K, CacheEntry> activeSegment, CacheEntry entry) {
    activeSegment.remove(entry.key);
    boolean removed = recencyQueue.remove(new Recency<>(entry.key, entry.expireTime));
    if (removed) {
      removalListener.onRemoval(
          RemovalNotification.create(entry.key, entry.value, RemovalCause.EXPIRED));
    }
  }

  private Object getLock(K key) {
    return segmentLocks[safeMod(key)];
  }

  private int safeMod(K key) {
    int segmentId = key.hashCode() % concurrencyLevel;
    return segmentId < 0 ? segmentId + concurrencyLevel : segmentId;
  }

  private HashMap<K, CacheEntry> getPendingSegment(K key) {
    return segments[safeMod(key) * 2];
  }

  private HashMap<K, CacheEntry> getActiveSegment(K key) {
    return segments[safeMod(key) * 2 + 1];
  }

  /** Performs an async RPC call if cached value doesn't exists. */
  @CheckReturnValue
  protected abstract ListenableFuture<V> rpcCall(K key);

  /**
   * Populates a PENDING cache entry in the pending segment.
   */
  private CacheEntry populateCache(final HashMap<K, CacheEntry> pendingSegment, final K key) {
    // all the put is though this method, perform clean up
    final ListenableFuture<V> future = rpcCall(key);
    final CacheEntry cacheEntry = new CacheEntry(key, future);
    // TODO consider copying when PENDING -> ACTIVE if no performance regression
    future.addListener(
        new Runnable() {
          @Override
          public void run() {
            if (future.isCancelled()) {
              return;
            }

            // moves cache entry from pending segment to active segment
            synchronized (getLock(key)) {
              CacheEntry pending = pendingSegment.remove(key);
              assert pending == cacheEntry : "race condition detected 2";

              try {
                V unused = future.get();
                // remove pending key
                recencyQueue.remove(new Recency<>(cacheEntry.key, cacheEntry.expireTime));
                // update cache's internal states when call is successfully finished
                long nowInMillis = ticker.nowInMillis();
                cacheEntry.expireTime = nowInMillis + maxAgeMillis;
                cacheEntry.staleTime = nowInMillis + staleAgeMillis;
                CacheEntry existing = getActiveSegment(key).put(key, cacheEntry);
                recencyQueue.add(new Recency<>(cacheEntry.key, cacheEntry.expireTime));
                assert existing == null || existing.isStaled(nowInMillis)
                    : "This is a bug! Race condition detected.";
              } catch (Exception e) {
                // no-op
              }
            }
          }
        },
        executor);
    CacheEntry existing = pendingSegment.put(key, cacheEntry);
    registerRecentKeyIfFull(cacheEntry);
    assert existing == null : "Race condition detected";
    return cacheEntry;
  }

  private CacheEntry registerRecentKeyIfFull(CacheEntry cacheEntry) {
    recencyQueue.add(new Recency<>(cacheEntry.key, cacheEntry.expireTime));
    if (recencyQueue.size() > maxSize) {
      removeRecent();
    }
    return cacheEntry;
  }

  private void removeRecent() {
    Iterator<Recency<K>> iter = recencyQueue.iterator();
    while (iter.hasNext() && recencyQueue.size() > maxSize) {
      Recency<K> next = iter.next();
      synchronized (getLock(next.key)) {
        iter.remove();
        CacheEntry toBeRemoved = getActiveSegment(next.key).remove(next.key);
        if (toBeRemoved != null && toBeRemoved.expireTime == next.expire) {
          removalListener.onRemoval(
              RemovalNotification.create(next.key, toBeRemoved.value, RemovalCause.SIZE));
        }
        toBeRemoved = getPendingSegment(next.key).remove(next.key);
        if (toBeRemoved != null && toBeRemoved.expireTime == next.expire) {
          removalListener.onRemoval(
              RemovalNotification.create(next.key, toBeRemoved.value, RemovalCause.SIZE));
        }
      }
    }
  }

  private void removeExpired(int maxRemoval) {
    System.out.println("trying to remove expired");
    Iterator<Recency<K>> iter = recencyQueue.iterator();
    long now = ticker.nowInMillis();
    int i = 0;
    while (iter.hasNext() && i < maxRemoval) {
      Recency<K> next = iter.next();
      if (next.expire > now) {
        break;
      }
      K key = next.key;
      synchronized (getLock(key)) {
        // 3 cases, both active pending, only active, only pending.
        // in any case, active need to be removed
        CacheEntry activeToBeRemoved = getActiveSegment(key).remove(key);
        CacheEntry pendingToBeRemoved = getPendingSegment(key).get(key);

        boolean refreshing = (activeToBeRemoved != null) && (pendingToBeRemoved != null);
        if (!refreshing) {
          if (pendingToBeRemoved != null && pendingToBeRemoved.isExpired(now)) {
            getActiveSegment(key).remove(key);
          }
          iter.remove();
          CacheEntry toBeRemoved = activeToBeRemoved != null ? activeToBeRemoved : pendingToBeRemoved;
          removalListener.onRemoval(
              RemovalNotification.create(key, toBeRemoved.value, RemovalCause.EXPIRED));
        }
      }
      i++;
    }
  }

  public final void cleanUp() {
    cleanUp(ticker.nowInMillis());
  }

  private void cleanUp(long now) {
    Iterator<Recency<K>> iter = recencyQueue.iterator();
    while (iter.hasNext()) {
      Recency<K> next = iter.next();
      if (next.expire <= now) {
        synchronized (getLock(next.key)) {
          iter.remove();
          CacheEntry removed = getActiveSegment(next.key).remove(next.key);
          if (removed == null) {
            removed = getPendingSegment(next.key).remove(next.key);
          }
          assert removed != null : "potential race condition";
          removalListener.onRemoval(
              RemovalNotification
                  .create(removed.key, removed.value, RemovalCause.EXPIRED));
        }
      }
    }
  }

  private final class CacheEntry {
    private final K key;
    private final ListenableFuture<V> value;
    private long expireTime;
    private long staleTime;

    CacheEntry(K key, ListenableFuture<V> value) {
      this.key = checkNotNull(key, "key");
      this.value = checkNotNull(value, "value");
      // TTLs will be updated to actual value when the call is succeeded
      expireTime = ticker.nowInMillis() + callTimeoutMillis;
      staleTime = expireTime;
    }

    boolean isExpired(long now) {
      return expireTime <= now;
    }

    boolean isStaled(long now) {
      return staleTime <= now;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("value", value)
          .add("expireTime", expireTime)
          .add("staleTime", staleTime)
          .toString();
    }
  }

  private static final class Recency<KeyT> {
    private final KeyT key;
    private final long expire;

    public Recency(KeyT key, long expire) {
      this.key = key;
      this.expire = expire;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Recency<?> recency = (Recency<?>) o;
      return expire == recency.expire && Objects.equal(key, recency.key);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(key, expire);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("k", key)
          .add("expire", expire)
          .toString();
    }
  }
}
