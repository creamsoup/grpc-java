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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.internal.FakeClock;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class AsyncRequestCacheTest {

  private static final int CACHE_SIZE = 10;
  private static final int RPC_LATENCY_IN_SEC = 1;
  private static final int EXPIRE_IN_SEC = 10;
  private static final int STALE_IN_SEC = 6;
  private static final int RPC_TIMEOUT = 2;

  private final FakeClock fakeClock = new FakeClock();
  private final DelegatingTicker ticker = new DelegatingTicker(fakeClock);
  @SuppressWarnings("unchecked")
  private final RemovalListener<String, ListenableFuture<String>> removalListener =
      mock(RemovalListener.class);
  private final AsyncRequestCache3<String, String> cache = new TestAsyncRlsCache();

  @Test
  public void cache_fullCycle() throws Exception {
    // new Key, should be PENDING for 1 sec
    ListenableFuture<String> res = cache.get("foo");
    fakeClock.forwardTime(999, TimeUnit.MILLISECONDS);
    assertThat(res.isDone()).isFalse();
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    assertThat(res.isDone()).isTrue();
    assertThat(res.get()).endsWith("Future1");

    // cache hit
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    ListenableFuture<String> hit = cache.get("foo");
    assertThat(hit.isDone()).isTrue();
    assertThat(res.get()).endsWith("Future1");

    // cache hit, staled
    fakeClock.forwardTime(6, TimeUnit.SECONDS);
    hit = cache.get("foo");
    assertThat(hit.isDone()).isTrue();
    assertThat(res.get()).endsWith("Future1");

    // async refresh finished
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    hit = cache.get("foo");
    assertThat(hit.isDone()).isTrue();
    assertThat(hit.get()).endsWith("Future2");

    // expired, load new
    fakeClock.forwardTime(20, TimeUnit.SECONDS);
    ListenableFuture<String> miss = cache.get("foo");
    assertThat(miss.isDone()).isFalse();
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(miss.get()).endsWith("Future3");
  }

  @Test
  public void cache_twoKeys() throws Exception {
    ListenableFuture<String> first = cache.get("foo");
    fakeClock.forwardTime(500, TimeUnit.MILLISECONDS);

    ListenableFuture<String> second = cache.get("bar");
    fakeClock.forwardTime(500, TimeUnit.MILLISECONDS);

    assertThat(first.isDone()).isTrue();
    assertThat(first.get()).endsWith("Future1");
    assertThat(second.isDone()).isFalse();

    fakeClock.forwardTime(500, TimeUnit.MILLISECONDS);
    assertThat(second.isDone()).isTrue();

    assertThat(first.get()).startsWith("foo");
    assertThat(second.get()).startsWith("bar");
  }

  @Test
  public void cache_lruEviction() throws Exception {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<RemovalNotification<String, ListenableFuture<String>>> captor =
        ArgumentCaptor.forClass(RemovalNotification.class);

    for (int key = 0; key < CACHE_SIZE * 2; key++) {
      ListenableFuture<String> unused = cache.get("" + key);
    }

    verify(removalListener, times(CACHE_SIZE)).onRemoval(captor.capture());
    int expectedEvictedKey = 0;
    for (RemovalNotification<String, ListenableFuture<String>> evicted : captor.getAllValues()) {
      assertThat(evicted.getCause()).isEqualTo(RemovalCause.SIZE);
      assertThat(Integer.parseInt(evicted.getKey())).isEqualTo(expectedEvictedKey++);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cache_expiredEviction() throws Exception {
    ListenableFuture<String> unused = cache.get("foo");
    cache.cleanUp();

    verify(removalListener, never()).onRemoval(any(RemovalNotification.class));

    ArgumentCaptor<RemovalNotification<String, ListenableFuture<String>>> captor =
        ArgumentCaptor.forClass(RemovalNotification.class);

    fakeClock.forwardTime(RPC_LATENCY_IN_SEC, TimeUnit.SECONDS);
    fakeClock.forwardTime(EXPIRE_IN_SEC, TimeUnit.SECONDS);
    cache.cleanUp();

    verify(removalListener).onRemoval(captor.capture());
    RemovalNotification<String, ListenableFuture<String>> removalNotice = captor.getValue();
    assertThat(removalNotice.wasEvicted()).isTrue();
    assertThat(removalNotice.getKey()).isEqualTo("foo");
    assertThat(removalNotice.getCause()).isEqualTo(RemovalCause.EXPIRED);
  }

  private final class TestAsyncRlsCache extends AsyncRequestCache3<String, String> {

    private final ScheduledExecutorService ses = fakeClock.getScheduledExecutorService();
    private final AtomicLong version = new AtomicLong();

    TestAsyncRlsCache() {
      super(
          fakeClock.getScheduledExecutorService(),
          TimeUnit.SECONDS.toMillis(EXPIRE_IN_SEC),
          TimeUnit.SECONDS.toMillis(STALE_IN_SEC),
          CACHE_SIZE,
          TimeUnit.SECONDS.toMillis(RPC_TIMEOUT),
          ticker,
          removalListener);
    }

    @Override
    protected ListenableFuture<String> rpcCall(final String key) {
      final SettableFuture<String> callFuture = SettableFuture.create();
      ScheduledFuture<?> unused = ses.schedule(new Runnable() {
        @Override
        public void run() {
          callFuture.set(key + "Future" + version.incrementAndGet());
        }
      }, RPC_LATENCY_IN_SEC, TimeUnit.SECONDS);
      return callFuture;
    }
  }

  private static final class DelegatingTicker implements Ticker {

    private final FakeClock clock;

    DelegatingTicker(FakeClock clock) {
      this.clock = clock;
    }

    @Override
    public long nowInMillis() {
      return clock.currentTimeMillis();
    }
  }
}