/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.netty.del;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Main purpose of this promise is bypassing netty's check of channel since actual work is done in
 * the underlying channel. The promise's channel may not match to the underlying channel which can
 * cause IllegalStateException.
 */
final class DelegatingChannelPromise implements ChannelPromise {

  private final Channel channel;
  private final ChannelPromise delegate;

  private DelegatingChannelPromise(Channel channel, ChannelPromise promise) {
    this.channel = channel;
    this.delegate = promise;
  }

  public static ChannelPromise of(Channel channel, ChannelPromise promise) {
    if (promise.channel() == channel) {
      return promise;
    }
    return new DelegatingChannelPromise(channel, promise);
  }

  @Override
  public Channel channel() {
    return channel;
  }

  // pure delegates

  @Override
  public ChannelPromise setSuccess(Void result) {
    return delegate.setSuccess(result);
  }

  @Override
  public ChannelPromise setSuccess() {
    return delegate.setSuccess();
  }

  @Override
  public boolean trySuccess() {
    return delegate.trySuccess();
  }

  @Override
  public boolean trySuccess(Void result) {
    return delegate.trySuccess(result);
  }

  @Override
  public ChannelPromise setFailure(Throwable cause) {
    return delegate.setFailure(cause);
  }

  @Override
  public ChannelPromise addListener(
      GenericFutureListener<? extends Future<? super Void>> listener) {
    return delegate.addListener(listener);
  }

  @Override
  @SuppressWarnings({"unchecked", "varargs"})
  public final ChannelPromise addListeners(
      GenericFutureListener<? extends Future<? super Void>>... listeners) {
    return delegate.addListeners(listeners);
  }

  @Override
  public ChannelPromise removeListener(
      GenericFutureListener<? extends Future<? super Void>> listener) {
    return delegate.removeListener(listener);
  }

  @Override
  @SuppressWarnings({"unchecked", "varargs"})
  public final ChannelPromise removeListeners(
      GenericFutureListener<? extends Future<? super Void>>... listeners) {
    return delegate.removeListeners(listeners);
  }

  @Override
  public ChannelPromise sync() throws InterruptedException {
    return delegate.sync();
  }

  @Override
  public ChannelPromise syncUninterruptibly() {
    return delegate.syncUninterruptibly();
  }

  @Override
  public ChannelPromise awaitUninterruptibly() {
    return delegate.awaitUninterruptibly();
  }

  @Override
  public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
    return delegate.awaitUninterruptibly(timeout, unit);
  }

  @Override
  public boolean awaitUninterruptibly(long timeoutMillis) {
    return delegate.awaitUninterruptibly(timeoutMillis);
  }

  @Override
  public ChannelPromise unvoid() {
    return delegate.unvoid();
  }

  @Override
  public boolean isVoid() {
    return delegate.isVoid();
  }

  @Override
  public boolean isSuccess() {
    return delegate.isSuccess();
  }

  @Override
  public boolean isCancellable() {
    return delegate.isCancellable();
  }

  @Override
  public Throwable cause() {
    return delegate.cause();
  }

  @Override
  public ChannelPromise await() throws InterruptedException {
    return delegate.await();
  }

  @Override
  public boolean await(long timeoutMillis) throws InterruptedException {
    return delegate.await(timeoutMillis);
  }

  @Override
  public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.await(timeout, unit);
  }

  @Override
  public Void getNow() {
    return delegate.getNow();
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return delegate.cancel(mayInterruptIfRunning);
  }

  @Override
  public boolean isCancelled() {
    return delegate.isCancelled();
  }

  @Override
  public boolean isDone() {
    return delegate.isDone();
  }

  @Override
  public Void get() throws InterruptedException, ExecutionException {
    return delegate.get();
  }

  @Override
  public Void get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate.get(timeout, unit);
  }

  @Override
  public boolean tryFailure(Throwable cause) {
    return delegate.tryFailure(cause);
  }

  @Override
  public boolean setUncancellable() {
    return delegate.setUncancellable();
  }
}
