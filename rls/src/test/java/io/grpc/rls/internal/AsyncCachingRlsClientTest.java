//package io.grpc.rls.internal;
//
//import static com.google.common.truth.Truth.assertThat;
//
//import com.google.common.collect.ImmutableMap;
//import com.google.common.util.concurrent.ListenableFuture;
//import io.grpc.Server;
//import io.grpc.inprocess.InProcessChannelBuilder;
//import io.grpc.inprocess.InProcessServerBuilder;
//import io.grpc.internal.TimeProvider;
//import io.grpc.rls.internal.AsyncCachingRlsClient.CacheEntry;
//import io.grpc.rls.internal.AsyncCachingRlsClient.CachedResponse;
//import io.grpc.rls.internal.LruCache.EvictionListener;
//import io.grpc.rls.internal.RlsProtoData.RouteLookupRequest;
//import io.grpc.testing.GrpcCleanupRule;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.ExecutionException;
//import java.util.concurrent.Executor;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.TimeoutException;
//import java.util.concurrent.atomic.AtomicBoolean;
//import org.junit.After;
//import org.junit.Before;
//import org.junit.Rule;
//import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.junit.runners.JUnit4;
//import org.mockito.Mock;
//import org.mockito.junit.MockitoJUnit;
//import org.mockito.junit.MockitoRule;
//
//@RunWith(JUnit4.class)
//public class AsyncCachingRlsClientTest {
//
//  @Rule
//  public final MockitoRule mocks = MockitoJUnit.rule();
//  @Rule
//  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
//
//  private AsyncCachingRlsClient client;
//  private Server server;
//  private FakeScheduledExecutorService ses;
//  // private FakeScheduledExecutorServiceNooneShouldUse ses;
//  private TimeProvider timeProvider;
//  private FakeThrottler throttler;
//  @Mock
//  private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener;
//  private TestRlsServer testRlsServer;
//
//  @Before
//  public void setUp() throws Exception {
//    ses = new FakeScheduledExecutorService();
//    timeProvider = ses.getTimeProvider();
//    testRlsServer = new TestRlsServer(ses);
//    server =
//        InProcessServerBuilder
//            .forName("rls-server")
//            .addService(testRlsServer)
//            .build();
//    server.start();
//    throttler = new FakeThrottler();
//    client = AsyncCachingRlsClient.newBuilder()
//        .setChannel(InProcessChannelBuilder.forName("rls-server").build())
//        .setEvictionListener(evictionListener)
//        .setTimeProvider(timeProvider)
//        .setThrottler(throttler)
//        .build();
//  }
//
//  @After
//  public void tearDown() throws Exception {
//    server.shutdown();
//    server.awaitTermination();
//    // client.close();
//    // ses.shutdown();
//  }
//
//  @Test
//  public void get_pendingToData() {
//    System.out.println("t1");
//    throttler.setNextResult(false);
//    testRlsServer
//        .setNextResponse(DelayedValueOrError.forValue(new RouteLookupResponse("foo", "bar"), 90));
//    CachedResponse resp =
//        client.get(new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of()));
//    assertThat(resp.hasValidData()).isFalse();
//    assertThat(resp.hasError()).isFalse();
//
//    System.out.println("before advance");
//    ses.advance(110);
//    System.out.println("after advance");
//    resp =
//        client.get(new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of()));
//    System.out.println("resp: " + resp);
//    assertThat(resp.hasValidData()).isTrue();
//    assertThat(resp.hasError()).isFalse();
//    assertThat(resp.getHeaderData()).isEqualTo("bar");
//    System.out.println("end t1");
//  }
//
//  @Test
//  public void get_throttled() {
//    System.out.println("t3");
//    throttler.setNextResult(true);
//    testRlsServer
//        .setNextResponse(DelayedValueOrError.forValue(new RouteLookupResponse("foo", "bar"), 0));
//    CachedResponse resp =
//        client.get(new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of()));
//    assertThat(resp.hasValidData()).isFalse();
//    assertThat(resp.hasError()).isTrue();
//    assertThat(resp.getStatus().isOk()).isFalse();
//    assertThat(resp.getStatus().getCause()).hasCauseThat().isInstanceOf(ThrottledException.class);
//    System.out.println("end t3");
//  }
//
//  @Test
//  public void get_throttled_recoverAfterBackoff() {
//    System.out.println("t2");
//    throttler.setNextResult(true);
//    testRlsServer
//        .setNextResponse(DelayedValueOrError.forValue(new RouteLookupResponse("foo", "bar"), 10));
//    RouteLookupRequest request =
//        new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of());
//    CachedResponse resp = client.get(request);
//    // should be throttled by throttler (no latency)
//    assertThat(resp.hasValidData()).isFalse();
//    assertThat(resp.hasError()).isTrue();
//    assertThat(resp.getStatus().isOk()).isFalse();
//    assertThat(resp.getStatus().getCause()).hasCauseThat().isInstanceOf(ThrottledException.class);
//
//    throttler.setNextResult(false);
//    ses.advance(100);
//
//    resp = client.get(request);
//    // next try should happened, so now in pending
//    assertThat(resp.hasValidData()).isFalse();
//    assertThat(resp.hasError()).isFalse();
//
//    ses.advance(10);
//    resp = client.get(request);
//    // hit
//    assertThat(resp.hasValidData()).isTrue();
//    assertThat(resp.hasError()).isFalse();
//    assertThat(resp.getHeaderData()).isEqualTo("bar");
//    System.out.println("endt2");
//  }
//
//  ///////////////////////////// new style
//
//  // @Test
//  // public void pending_toData() {
//  //   RouteLookupRequest request =
//  //       new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of());
//  //   RouteLookupResponse response = new RouteLookupResponse("target", "header");
//  //   FakeListenableFuture<RouteLookupResponse> pendingCall =
//  //       new FakeListenableFuture<>(response, 100);
//  //   PendingCacheEntry unused = client.createPendingEntry(request, pendingCall);
//  //
//  //   // triggers scheduled callback which will add data entry the cache
//  //   ses.advance(100);
//  //   assertThat(pendingCall.executePendingListeners()).isTrue();
//  //
//  //   CachedResponse dataEntry = client.get(request);
//  //   assertThat(dataEntry.hasValidData()).isTrue();
//  // }
//  //
//  // @Test
//  // public void pending_toBackoff() {
//  //   RouteLookupRequest request =
//  //       new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of());
//  //   FakeListenableFuture<RouteLookupResponse> pendingCall =
//  //       new FakeListenableFuture<>(new StackOverflowError(), 100);
//  //   PendingCacheEntry unused = client.createPendingEntry(request, pendingCall);
//  //
//  //   // triggers scheduled callback which will add backoff entry the cache
//  //   ses.advance(100);
//  //   assertThat(pendingCall.executePendingListeners()).isTrue();
//  //
//  //   CachedResponse foo = client.get(request);
//  //   assertThat(foo.hasError()).isTrue();
//  // }
//  //
//  // @Test
//  // public void data_staled() {
//  //   RouteLookupRequest request =
//  //       new RouteLookupRequest("foo", "Bar", "grpc", ImmutableMap.<String, String>of());
//  //   RouteLookupResponse response = new RouteLookupResponse("target", "header");
//  //   FakeListenableFuture<RouteLookupResponse> pendingCall =
//  //       new FakeListenableFuture<>(response, 100);
//  //   PendingCacheEntry unused = client.createPendingEntry(request, pendingCall);
//  //
//  //   // triggers scheduled callback which will add data entry the cache
//  //   ses.advance(100);
//  //   assertThat(pendingCall.executePendingListeners()).isTrue();
//  //
//  //   CachedResponse dataEntry = client.get(request);
//  //   assertThat(dataEntry.hasValidData()).isTrue();
//  //
//  //
//  // }
//
//  @Test
//  public void data_backOff() {
//
//  }
//
//  @Test
//  public void data_staled_pending() {
//
//  }
//
//  @Test
//  public void backoff_pending() {
//
//  }
//
//  /////////////////////////////
//
//  private static class FakeThrottler implements Throttler {
//
//    private AtomicBoolean nextResult = new AtomicBoolean();
//
//    public void setNextResult(boolean next) {
//      nextResult.set(next);
//    }
//
//    @Override
//    public boolean shouldThrottle() {
//      return nextResult.get();
//    }
//
//    @Override
//    public void registerBackendResponse(boolean throttled) {
//
//    }
//  }
//
//  private class FakeListenableFuture<T> implements ListenableFuture<T> {
//
//    private final T futureValue;
//    private final Throwable error;
//    private final long futureAvailableTime;
//    private final List<Runnable> listeners = new ArrayList<>();
//
//    public FakeListenableFuture(T futureValue, long delay) {
//      this.futureValue = futureValue;
//      this.futureAvailableTime = timeProvider.nowInMillis() + delay;
//      error = null;
//    }
//
//    public FakeListenableFuture(Throwable error, long delay) {
//      this.futureValue = null;
//      this.error = error;
//      this.futureAvailableTime = timeProvider.nowInMillis() + delay;
//    }
//
//    @Override
//    public synchronized void addListener(Runnable listener, Executor executor) {
//      if (isDone()) {
//        executePendingListeners();
//        listener.run();
//      } else {
//        listeners.add(listener);
//      }
//    }
//
//    synchronized boolean executePendingListeners() {
//      if (!isDone()) {
//        return false;
//      }
//      boolean executed = false;
//      for (Runnable listener : listeners) {
//        listener.run();
//        executed = true;
//      }
//      listeners.clear();
//      return executed;
//    }
//
//    @Override
//    public boolean cancel(boolean b) {
//      throw new UnsupportedOperationException();
//    }
//
//    @Override
//    public boolean isCancelled() {
//      return false;
//    }
//
//    @Override
//    public boolean isDone() {
//      return futureAvailableTime <= timeProvider.nowInMillis();
//    }
//
//    @Override
//    public T get() throws InterruptedException, ExecutionException {
//      Thread.sleep(futureAvailableTime - timeProvider.nowInMillis());
//      if (futureValue != null) {
//        return futureValue;
//      } else {
//        throw new ExecutionException(error);
//      }
//    }
//
//    @Override
//    public T get(long l, TimeUnit timeUnit)
//        throws InterruptedException, ExecutionException, TimeoutException {
//      Thread.sleep(Math.min(l, futureAvailableTime - timeProvider.nowInMillis()));
//      if (isDone()) {
//        if (futureValue != null) {
//          return futureValue;
//        } else {
//          throw new ExecutionException(error);
//        }
//      } else {
//        throw new TimeoutException();
//      }
//    }
//  }
//}
