//package io.grpc.rls;
//
//import static java.util.concurrent.TimeUnit.MILLISECONDS;
//import static java.util.concurrent.TimeUnit.NANOSECONDS;
//
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.Iterator;
//import java.util.List;
//import java.util.concurrent.AbstractExecutorService;
//import java.util.concurrent.Callable;
//import java.util.concurrent.Delayed;
//import java.util.concurrent.Executors;
//import java.util.concurrent.FutureTask;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.ScheduledFuture;
//import java.util.concurrent.TimeUnit;
//
//public final class FakeSes extends AbstractExecutorService implements ScheduledExecutorService {
//  public FakeSes() {}
//
//  private final List<Job<?>> jobs = Collections.synchronizedList(new ArrayList<Job<?>>());
//  private long offsetNanos = 0;
//
//  //Call this to advance the clock...
//  public synchronized void advance(long timeInMillis) {
//    advance(timeInMillis, MILLISECONDS);
//  }
//
//  public synchronized void advance(long time, TimeUnit timeUnit) {
//    offsetNanos += NANOSECONDS.convert(time, timeUnit);
//
//    Iterator<Job<?>> iter = jobs.iterator();
//    while(iter.hasNext()) {
//      Job<?> job = iter.next();
//      if(offsetNanos >= job.initialDelayNanos) {
//        job.run();
//        iter.remove();
//      }
//    }
//  }
//
//  private synchronized <V> ScheduledFuture<V> scheduleInternal(Callable<V> callable, long delay, long period, TimeUnit timeUnit) {
//    Job<V> job = new Job<>(callable, offsetNanos + NANOSECONDS.convert( delay, timeUnit), NANOSECONDS.convert(period, timeUnit));
//    jobs.add(job);
//    return job;
//  }
//
//
//  @Override
//  public ScheduledFuture<?> schedule(Runnable runnable, long delay, TimeUnit timeUnit) {
//    return schedule(Executors.callable(runnable, null), delay, timeUnit);
//  }
//
//  @Override
//  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit timeUnit) {
//    return scheduleInternal(callable, delay, 0, timeUnit);
//  }
//
//  @Override
//  public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, long delay, long period, TimeUnit timeUnit) {
//    return scheduleInternal(Executors.callable(runnable, null), delay, Math.abs(period), timeUnit);
//  }
//
//  @Override
//  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable runnable, long delay, long period, TimeUnit timeUnit) {
//    return scheduleInternal(Executors.callable(runnable, null), delay, -Math.abs(period), timeUnit);
//  }
//
//  public Ticker getFakeTicker() {
//    return new FakeTicker();
//  }
//
//  class FakeTicker implements Ticker {
//
//    @Override
//    public long nowInMillis() {
//      return NANOSECONDS.toMillis(offsetNanos);
//    }
//  }
//
//
//  class Job<V> extends FutureTask<V> implements ScheduledFuture<V> {
//    final Callable<V> task;
//    final long initialDelayNanos;
//    final long periodNanos;
//
//    public Job(Callable<V> runner, long initialDelayNanos, long periodNanos) {
//      super(runner);
//      this.task = runner;
//      this.initialDelayNanos = initialDelayNanos;
//      this.periodNanos = periodNanos;
//    }
//    @Override public long getDelay(TimeUnit timeUnit) {return timeUnit.convert(initialDelayNanos, NANOSECONDS);}
//    @Override public int compareTo(Delayed delayed) {throw new RuntimeException();} //Need to implement this to fix ordering.
//
//    @Override public void run() {
//      if(periodNanos == 0) {
//        super.run();
//      } else {
//        //If this task is periodic and it runs ok, then reschedule it.
//        if(super.runAndReset()) {
//          jobs.add(reschedule(offsetNanos));
//        }
//      }
//    }
//
//    private Job<V> reschedule(long offset) {
//      if(periodNanos < 0) return new Job<V>(task, offset, periodNanos); //fixed delay
//      long newDelay = initialDelayNanos;  while(newDelay <= offset) newDelay += periodNanos; //fixed rate
//      return new Job<V>(task, newDelay, periodNanos);
//    }
//  }
//
//  @Override public void execute(Runnable command) { schedule(command, 0, NANOSECONDS); }
//  @Override public void shutdown() {}
//  @Override public List<Runnable> shutdownNow() { throw new RuntimeException(); }
//  @Override public boolean isShutdown() { return false;}
//  @Override public boolean isTerminated() { return false;}
//  @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
//}