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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static io.opencensus.internal.Utils.checkNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.rls.AdaptiveThrottler.Ticker;
import io.grpc.rls.LruCache.RemovalListener;
import io.grpc.rls.LruCache.RemovalReason;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class LruCacheTest {

  private static final int MAX_SIZE = 5;

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final FakeScheduledService fses = mock(FakeScheduledService.class, CALLS_REAL_METHODS);
  private final Ticker ticker = fses.getFakeTicker();

  @Mock
  private RemovalListener<Integer, Entry> removalListener;
  private LruCache<Integer, Entry> cache;

  @Before
  public void setUp() throws Exception {

    this.cache = new LruCache<Integer, Entry>(
        MAX_SIZE,
        removalListener,
        10,
        TimeUnit.MILLISECONDS,
        fses,
        ticker) {
      @Override
      protected boolean isExpired(Integer key, Entry value, long nowInMillis) {
        return value.getExpireTime() <= nowInMillis;
      }
    };
  }

  @Test
  public void eviction_size() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      cache.put(i, new Entry("Entry" + i, Long.MAX_VALUE));
    }
    cache.put(MAX_SIZE + 1, new Entry("should kick the first", Long.MAX_VALUE));

    verify(removalListener).onRemoval(1, new Entry("Entry1", Long.MAX_VALUE), RemovalReason.SIZE);
    assertThat(cache.size()).isEqualTo(MAX_SIZE);
  }

  @Test
  public void size() {
    Entry entry1 = new Entry("Entry0", ticker.nowInMillis() + 10);
    Entry entry2 = new Entry("Entry1", ticker.nowInMillis() + 20);
    cache.put(0, entry1);
    cache.put(1, entry2);
    assertThat(cache.size()).isEqualTo(2);

    assertThat(cache.remove(0)).isEqualTo(entry1);
    assertThat(cache.size()).isEqualTo(1);

    assertThat(cache.remove(1)).isEqualTo(entry2);
    assertThat(cache.size()).isEqualTo(0);
  }

  @Test
  public void eviction_expire() {
    Entry toBeEvicted = new Entry("Entry0", ticker.nowInMillis() + 10);
    Entry survivor = new Entry("Entry1", ticker.nowInMillis() + 20);
    cache.put(0, toBeEvicted);
    cache.put(1, survivor);

    fses.advance(10);
    verify(removalListener).onRemoval(0, toBeEvicted, RemovalReason.EXPIRED);

    fses.advance(10);
    verify(removalListener).onRemoval(1, survivor, RemovalReason.EXPIRED);
  }

  @Test
  public void eviction_explicit() {
    Entry toBeEvicted = new Entry("Entry0", ticker.nowInMillis() + 10);
    Entry survivor = new Entry("Entry1", ticker.nowInMillis() + 20);
    cache.put(0, toBeEvicted);
    cache.put(1, survivor);

    assertThat(cache.remove(0)).isEqualTo(toBeEvicted);

    verify(removalListener).onRemoval(0, toBeEvicted, RemovalReason.EXPLICIT);
  }

  @Test
  public void eviction_replaced() {
    Entry toBeEvicted = new Entry("Entry0", ticker.nowInMillis() + 10);
    Entry survivor = new Entry("Entry1", ticker.nowInMillis() + 20);
    cache.put(0, toBeEvicted);
    cache.put(0, survivor);

    verify(removalListener).onRemoval(0, toBeEvicted, RemovalReason.REPLACED);
  }

  @Test
  public void eviction_size_shouldEvictAlreadyExpired() {
    for (int i = 1; i <= MAX_SIZE; i++) {
      cache.put(i, new Entry("Entry" + i, ticker.nowInMillis() + MAX_SIZE - i));
    }
    cache.put(MAX_SIZE + 1, new Entry("should kick the first", Long.MAX_VALUE));

    verify(removalListener).onRemoval(eq(MAX_SIZE), any(Entry.class), eq(RemovalReason.EXPIRED));
    assertThat(cache.size()).isEqualTo(MAX_SIZE);
  }

  private static class Entry {
    private String value;
    private long expireTime;

    public Entry(String value, long expireTime) {
      this.value = value;
      this.expireTime = expireTime;
    }

    public String getValue() {
      return value;
    }

    public long getExpireTime() {
      return expireTime;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Entry entry = (Entry) o;
      return expireTime == entry.expireTime && Objects.equal(value, entry.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value, expireTime);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("value", value)
          .add("expireTime", expireTime)
          .toString();
    }
  }

  // A fake ScheduledExecutorService only can handle one scheduledAtFixedRate with a lot of
  // limitation. Only intended to be used in this test.
  // NOTE: Intended to use with mock to call real methods.
  private static abstract class FakeScheduledService implements ScheduledExecutorService {

    private long currTimeInMillis;
    private long period;
    private long nextRun;
    private AtomicReference<Runnable> command;

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      if (this.command == null) {
        this.command = new AtomicReference<>();
      }
      checkState(this.command.get() == null, "only can schedule one");
      checkState(period > 0, "period should be positive");
      checkState(initialDelay >= 0, "initial delay should be >= 0");
      this.command.set(checkNotNull(command, "command"));
      if (initialDelay == 0) {
        initialDelay = period;
        command.run();
      }
      this.nextRun = checkNotNull(unit, "unit").toMillis(initialDelay) + currTimeInMillis;
      this.period = unit.toMillis(period);
      return mock(ScheduledFuture.class);
    }

    Ticker getFakeTicker() {
      return new FakeTicker();
    }

    void advance(long millis) {
      currTimeInMillis += millis;
      Runnable cmd = command.get();
      if (cmd != null && currTimeInMillis >= nextRun) {
        nextRun += period;
        cmd.run();
      }
    }

    private class FakeTicker implements Ticker {
      @Override
      public long nowInMillis() {
        return currTimeInMillis;
      }
    }
  }
}