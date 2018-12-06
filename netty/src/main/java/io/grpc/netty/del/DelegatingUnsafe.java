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

import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.VoidChannelPromise;
import java.net.SocketAddress;

/** Although the name is DelegatingUnsafe, it doesn't delegate all the methods. */
@SuppressWarnings("FutureReturnValueIgnored")
final class DelegatingUnsafe implements Unsafe {

  private final DelegatingChannel delegatingChannel;
  private final VoidChannelPromise voidChannelPromise;

  DelegatingUnsafe(DelegatingChannel delegatingChannel) {
    this.delegatingChannel = delegatingChannel;
    this.voidChannelPromise = new VoidChannelPromise(delegatingChannel, false);
  }

  private Unsafe unsafe() {
    return delegatingChannel.underlyingChannel().unsafe();
  }

  @Override
  @Deprecated
  public RecvByteBufAllocator.Handle recvBufAllocHandle() {
    return unsafe().recvBufAllocHandle();
  }

  @Override
  public ChannelOutboundBuffer outboundBuffer() {
    return unsafe().outboundBuffer();
  }

  @Override
  public ChannelPromise voidPromise() {
    return voidChannelPromise;
  }

  @Override
  public SocketAddress localAddress() {
    return unsafe().localAddress();
  }

  @Override
  public SocketAddress remoteAddress() {
    return unsafe().remoteAddress();
  }

  @Override
  public void register(EventLoop eventLoop, ChannelPromise promise) {
    unsafe()
        .register(
            eventLoop, createDelegateInternalPromise(promise));
  }

  private ChannelPromise createDelegateInternalPromise(ChannelPromise promise) {
    return DelegatingChannelPromise.of(delegatingChannel.underlyingChannel(), promise);
  }

  @Override
  public void bind(SocketAddress localAddress, ChannelPromise promise) {
    unsafe().bind(localAddress, promise);
  }

  @Override
  public void connect(
      SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
    unsafe().connect(remoteAddress, localAddress, promise);
  }

  @Override
  public void disconnect(ChannelPromise promise) {
    unsafe().disconnect(promise);
  }

  @Override
  public void deregister(ChannelPromise promise) {
    unsafe().deregister(promise);
  }

  @Override
  public void beginRead() {
    unsafe().beginRead();
  }

  @Override
  public void write(Object msg, ChannelPromise promise) {
    unsafe()
        .write(msg, createDelegateInternalPromise(promise));
  }

  @Override
  public void flush() {
    unsafe().flush();
  }

  @Override
  public void close(ChannelPromise promise) {
    delegatingChannel.close(promise);
  }

  @Override
  public void closeForcibly() {
    unsafe().closeForcibly();
  }
}
