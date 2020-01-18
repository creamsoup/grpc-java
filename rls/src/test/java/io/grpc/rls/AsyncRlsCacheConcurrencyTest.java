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

import static io.opencensus.internal.Utils.checkState;

import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Range;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@Ignore
public class AsyncRlsCacheConcurrencyTest {
  private static final Range<Integer> RPC_LATENCY_MILLIS = Range.closedOpen(2, 20);
  private static final int CACHE_SIZE = 100;
  private static final int EXPIRE_MILLIS = 200;
  private static final int STALE_MILLIS = (int) (EXPIRE_MILLIS * 0.8);
  // for now, requests won't fail.
  // TODO(jihuncho) add failure cases
  private static final int RPC_TIMEOUT_IN_MILLIS = 30;
  private static final Random random = new Random();
  private static final int NUM_SYSTEM_THREADS = 1;//Runtime.getRuntime().availableProcessors();
  private static final int NUM_WORKER = NUM_SYSTEM_THREADS;
  private final ScheduledExecutorService ses =
      Executors.newScheduledThreadPool(NUM_SYSTEM_THREADS);
  private final Ticker ticker = new Ticker() {
    long start = System.currentTimeMillis();

    @Override
    public long nowInMillis() {
      return System.currentTimeMillis() - start;
    }
  };
  private final ConcurrentAsyncRequestCache<String, String> cache = new TestConcurrentAsyncRlsCache();
  // reasonable larger than cache size to test eviction case as well.
  private final Range<Integer> keyRange = Range.closedOpen(0, (int) (CACHE_SIZE * 1.2));

  private List<CacheWorker> cacheWorkers;
  private Executor executor = Executors.newFixedThreadPool(NUM_WORKER);
  private List<AtomicLong> atomicLongs = new ArrayList<>();
  private AtomicLong rpcCallCount = new AtomicLong();
  private AtomicLong evictedKeyCnt = new AtomicLong();
  private long startTime;

  @Before
  public void setUp() {
    cacheWorkers = new ArrayList<>();
    for (int i = 0; i < NUM_WORKER; i++) {
      AtomicLong atomicLong = new AtomicLong();
      atomicLongs.add(atomicLong);
      cacheWorkers.add(new CacheReader(atomicLong));
    }
  }

  @Test
  public void runConcurrentTest() throws Exception {
    startTime = ticker.nowInMillis();
    for (CacheWorker worker : cacheWorkers) {
      executor.execute(worker);
    }
    ScheduledFuture<?> unused = ses.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        long total = 0L;
        for (AtomicLong atomicLong : atomicLongs) {
          total += atomicLong.get();
        }
        long rpcs = rpcCallCount.get();
        long evicted = evictedKeyCnt.get();
        long now = ticker.nowInMillis();
        long elapsedSec = TimeUnit.MILLISECONDS.toSeconds(now - startTime);
        System.out.println("===============================");
        System.out.println("total " + total + " lookups");
        System.out.println("total " + rpcs + " rpcs");
        System.out.println("eviction " + evicted);
        System.out.println("time elapsed " + elapsedSec + " s.");
        System.out.println("running cache RPS: " + (total / elapsedSec));
        System.out.println("running RPC PS: " + (rpcs / elapsedSec));
        System.out.println("running Eviction PS: " + (evicted / elapsedSec));
        System.out.println("===============================");

      }
    }, 10, 10, TimeUnit.SECONDS);

    ScheduledFuture<?> shutdown = ses.schedule(new Runnable() {
      @Override
      public void run() {
        for (CacheWorker worker : cacheWorkers) {
          worker.shutdown();
        }
      }
    }, 5, TimeUnit.MINUTES);
    shutdown.get();
  }

  private static abstract class CacheWorker implements Runnable {

    AtomicBoolean shutdown = new AtomicBoolean(false);

    @Override
    public void run() {
      while (!shutdown.get()) {
        doWork();
      }
    }

    abstract void doWork();

    public void shutdown() {
      shutdown.set(true);
    }
  }

  private final class CacheReader extends CacheWorker {

    private final AtomicLong atomicLong;

    public CacheReader(AtomicLong atomicLong) {
      this.atomicLong = atomicLong;
    }

    @Override
    void doWork() {
      String randomKey = "" +
          (int) (keyRange.lowerEndpoint()
              + random.nextDouble() * (keyRange.upperEndpoint() - keyRange.lowerEndpoint()));
      // Stopwatch stopwatch = Stopwatch.createStarted();
      ListenableFuture<String> unused = cache.get(randomKey);// + atomicLong.get());
      atomicLong.incrementAndGet();
      // histogram[(int) Math.min(100, stopwatch.elapsed(TimeUnit.NANOSECONDS)/100) - 1]
      //     .incrementAndGet();
    }
  }

  private final ConcurrentMap<String, Long> keyToValidTimeMap =
      new ConcurrentHashMap<>();


  private final class TestConcurrentAsyncRlsCache extends
      ConcurrentAsyncRequestCache<String, String> {

    private final AtomicLong version = new AtomicLong();

    TestConcurrentAsyncRlsCache() {
      super(
          AsyncRlsCacheConcurrencyTest.this.ses,
          EXPIRE_MILLIS,
          STALE_MILLIS,
          CACHE_SIZE,
          RPC_TIMEOUT_IN_MILLIS,
          AsyncRlsCacheConcurrencyTest.this.ticker,
          new RemovalHandler());
    }

    @Override
    protected ListenableFuture<String> rpcCall(final String key) {
      final SettableFuture<String> callFuture = SettableFuture.create();
      long now = ticker.nowInMillis();
      int latency =
          (int) (random.nextDouble()
              * (RPC_LATENCY_MILLIS.upperEndpoint() - RPC_LATENCY_MILLIS.lowerEndpoint())
              + RPC_LATENCY_MILLIS.lowerEndpoint());
      System.out.println("new Call for " + key + " @" + now + " with latency " + latency);

      Long previousCallTime = keyToValidTimeMap.put(key, now);
      if (previousCallTime != null) {
        checkState(
            previousCallTime + STALE_MILLIS <= now,
            String.format(
                "New call for %s is made before staled previous: %s, stale millis: %s, now: %s, "
                    + "latency: %s",
                key,
                previousCallTime,
                STALE_MILLIS,
                now,
                latency));
      }

      ScheduledFuture<?> unused = ses.schedule(new Runnable() {
        @Override
        public void run() {
          callFuture.set(key + "Future" + version.incrementAndGet());
        }
      }, latency, TimeUnit.MILLISECONDS);
      rpcCallCount.incrementAndGet();
      return callFuture;
    }
  }

  private final class RemovalHandler implements RemovalListener<String, ListenableFuture<String>> {

    @Override
    public void onRemoval(
        RemovalNotification<String, ListenableFuture<String>> notification) {
      System.out.println("removing key: " + notification.getKey() + " " + notification.getCause());
      keyToValidTimeMap.remove(notification.getKey());
      evictedKeyCnt.incrementAndGet();
    }
  }
}