//package io.grpc.rls;
//
//import static com.google.common.base.Preconditions.checkArgument;
//import static com.google.common.base.Preconditions.checkNotNull;
//import static com.google.common.base.Preconditions.checkState;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.when;
//
//import java.util.ArrayList;
//import java.util.Collection;
//import java.util.Iterator;
//import java.util.List;
//import java.util.concurrent.Callable;
//import java.util.concurrent.ExecutionException;
//import java.util.concurrent.Future;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.ScheduledFuture;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.TimeoutException;
//import java.util.concurrent.atomic.AtomicReference;
//
///**
// * A fake minimal implementation of ScheduledExecutorService *only* supports scheduledAtFixedRate
// * and schedule with a lot of limitation / assumptions. Only intended to be used in this test with
// * CALL_REAL_METHODS mock.
// *
// * DO NOT ATTEMPT TO USE THIS
// */
//class FakeScheduledExecutorServiceNooneShouldUse implements ScheduledExecutorService {
//
//  private long currTimeInMillis;
//  private long fixedRate;
//  private long nextRepeatedRun;
//  private AtomicReference<Runnable> repeatedCommand;
//
//  private List<ScheduledTask> tasks;
//
//  @Override
//  public void execute(Runnable runnable) {
//
//  }
//
//  private static class ScheduledTask {
//    Runnable command;
//    long time;
//
//    public ScheduledTask(Runnable command, long time) {
//      this.command = command;
//      this.time = time;
//    }
//  }
//
//  private void maybeInit() {
//    if (repeatedCommand == null) {
//      repeatedCommand = new AtomicReference<>();
//    }
//    if (tasks == null) {
//      tasks = new ArrayList<>();
//    }
//  }
//
//  @Override
//  public ScheduledFuture<?> scheduleAtFixedRate(
//      Runnable command, long initialDelay, long period, TimeUnit unit) {
//    synchronized (this) {
//      System.out.println("scheduling initial " + initialDelay + " rate " + period);
//      maybeInit();
//      checkState(this.repeatedCommand.get() == null, "only can schedule one");
//      checkState(period > 0, "period should be positive");
//      checkState(initialDelay >= 0, "initial delay should be >= 0");
//      if (initialDelay == 0) {
//        initialDelay = period;
//        command.run();
//      }
//      this.repeatedCommand.set(checkNotNull(command, "command"));
//      this.nextRepeatedRun = checkNotNull(unit, "unit").toMillis(initialDelay) + currTimeInMillis;
//      this.fixedRate = unit.toMillis(period);
//      return getMockScheduledFuture();
//    }
//  }
//
//  @Override
//  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable runnable, long l, long l1,
//      TimeUnit timeUnit) {
//    return null;
//  }
//
//  @Override
//  public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
//    synchronized (this) {
//      System.out.println("scheduling after " + delay);
//      maybeInit();
//      if (delay == 0) {
//        command.run();
//        return getMockScheduledFuture();
//      }
//      synchronized (tasks) {
//        tasks.add(new ScheduledTask(checkNotNull(command, "command"),
//            checkNotNull(unit, "unit").toMillis(delay) + currTimeInMillis));
//      }
//      return getMockScheduledFuture();
//    }
//  }
//
//  @Override
//  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long l, TimeUnit timeUnit) {
//    return null;
//  }
//
//  private ScheduledFuture getMockScheduledFuture() {
//    ScheduledFuture scheduledFuture = mock(ScheduledFuture.class);
//    when(scheduledFuture.isCancelled()).thenReturn(false);
//    when(scheduledFuture.isDone()).thenReturn(false);
//    return scheduledFuture;
//  }
//
//  Ticker getFakeTicker() {
//    return new FakeTicker();
//  }
//
//  void advance(long millis) {
//    synchronized (this) {
//      maybeInit();
//      final long startTime = currTimeInMillis;
//      // handle repeated task
//      // if scheduled command, only can advance the ticker to trigger at most 1 event
//      boolean scheduled = repeatedCommand != null && repeatedCommand.get() != null;
//      if (scheduled) {
//        checkArgument(
//            (currTimeInMillis + millis) < (nextRepeatedRun + 2 * fixedRate),
//            "Cannot advance ticker because more than one repeated tasks will run");
//        long finalTime = currTimeInMillis + millis;
//        if (finalTime >= nextRepeatedRun) {
//          tasks.add(new ScheduledTask(repeatedCommand.get(), nextRepeatedRun));
//          nextRepeatedRun += fixedRate;
//        }
//        currTimeInMillis = finalTime;
//      } else {
//        currTimeInMillis += millis;
//      }
//
//      synchronized (tasks) {
//        Iterator<ScheduledTask> iter = tasks.iterator();
//        while (iter.hasNext()) {
//          ScheduledTask task = iter.next();
//          synchronized (task.command) {
//            if (task.time > startTime && task.time <= startTime + millis) {
//              System.out
//                  .println("running a scheduled task at " + task.time);
//              currTimeInMillis = task.time;
//              task.command.run();
//              currTimeInMillis = startTime + millis;
//              iter.remove();
//            }
//          }
//        }
//      }
//    }
//  }
//
//  @Override
//  public void shutdown() {
//    synchronized (this) {
//      maybeInit();
//      currTimeInMillis = 0;
//      fixedRate = 0;
//      nextRepeatedRun = 0;
//      tasks.clear();
//      repeatedCommand.set(null);
//    }
//  }
//
//  @Override
//  public List<Runnable> shutdownNow() {
//    return null;
//  }
//
//  @Override
//  public boolean isShutdown() {
//    return false;
//  }
//
//  @Override
//  public boolean isTerminated() {
//    return false;
//  }
//
//  @Override
//  public boolean awaitTermination(long l, TimeUnit timeUnit) throws InterruptedException {
//    return false;
//  }
//
//  @Override
//  public <T> Future<T> submit(Callable<T> callable) {
//    return null;
//  }
//
//  @Override
//  public <T> Future<T> submit(Runnable runnable, T t) {
//    return null;
//  }
//
//  @Override
//  public Future<?> submit(Runnable runnable) {
//    return null;
//  }
//
//  @Override
//  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> collection)
//      throws InterruptedException {
//    return null;
//  }
//
//  @Override
//  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> collection, long l,
//      TimeUnit timeUnit) throws InterruptedException {
//    return null;
//  }
//
//  @Override
//  public <T> T invokeAny(Collection<? extends Callable<T>> collection)
//      throws InterruptedException, ExecutionException {
//    return null;
//  }
//
//  @Override
//  public <T> T invokeAny(Collection<? extends Callable<T>> collection, long l, TimeUnit timeUnit)
//      throws InterruptedException, ExecutionException, TimeoutException {
//    return null;
//  }
//
//  private class FakeTicker implements Ticker {
//    @Override
//    public long nowInMillis() {
//      return currTimeInMillis;
//    }
//  }
//}
//
