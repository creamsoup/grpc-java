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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.net.SocketAddress;

/**
 * A {@link OutboundDelegatingHandler} is a {@link io.netty.channel.ChannelHandler} installed in the
 * {@link DelegatingChannel} unlike the {@link InboundDelegatingHandler} which installed in the
 * underlying channel. The {@code OutboundDelegatingHandler} is the last {@code ChannelHandler} in
 * outbound pipeline which delegates writes and events of the pipeline to internal channel in
 * {@code DelegatingChannel}.
 *
 * <pre>
 *  Overall data flow in {@link DelegatingChannel}
 *
 *  DelegatingChannel's Pipeline
 *  +-------------------------------------------------------------------------------+
 *  |                                                          |                    |
 *  |                                                         \|/                   |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |    |  Inbound Handler N         |         | Outbound Handler 1         |      |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |                  /|\                                     |                    |
 *  |                   |                                     \|/                   |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |    |  Inbound Handler N-1       |         |  Outbound Handler 2        |      |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |                  /|\                                     .                    |
 *  |                   .                                      .                    |
 *  |  ChannelHandlerContext.fireIN_EVT()       ChannelHandlerContext.OUT_EVT()     |
 *  |             [method call]                          [method call]              |
 *  |                   .                                      |                    |
 *  |                   .                                     \|/                   |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |    |  Inbound Handler 3         |         |  Outbound Handler N-1      |      |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |                  /|\                                     |                    |
 *  |                   |                                     \|/                   |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |    |  Inbound Handler 2         |         |  Outbound Handler N        |      |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |                  /|\                                     |                    |
 *  |                   |                                     \|/                   |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |    |  InboundHandler 1          |         |  OutboundDelegatingHandler |      |
 *  |    +--------------+-------------+         +--------------+-------------+      |
 *  |                   |                                      |                    |
 *  +-------------------+-----------------------------------------------------------+
 *  InternalChannel    /|\                                     |
 *  Pipeline            |                                     \|/
 *  +-------------------+-----------------------------------------------------------+
 *  |                  /|\                                     |                    |
 *  |                   |                                      |                    |
 *  |    +----------------------------+                        |                    |
 *  |    | InboundDelegatingHandler   |                        |                    |
 *  |    +----------------------------+                        |                    |
 *  |                   |                                     \|/                   |
 *  +-------------------+--------------------------------------+--------------------+
 *  Socket             /|\                                     |
 *  +-------------------+--------------------------------------+--------------------+
 *  |                   |                                      |                    |
 *  |           [ Socket.read() ]                      [ Socket.write() ]           |
 *  +-------------------------------------------------------------------------------+
 * </pre>
 */
@SuppressWarnings("FutureReturnValueIgnored")
final class OutboundDelegatingHandler extends ChannelOutboundHandlerAdapter {

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    Channel underlyingChannel = ((DelegatingChannel) ctx.channel())
        .underlyingChannel();
    underlyingChannel.pipeline()
        .write(msg, DelegatingChannelPromise.of(underlyingChannel, promise));
  }

  @Override
  public void flush(ChannelHandlerContext ctx) throws Exception {
    ((DelegatingChannel)ctx.channel()).underlyingChannel().pipeline().flush();
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    ctx.fireUserEventTriggered(new DelegatingChannel.InternalCloseEvent());
    super.close(ctx, promise);
  }

  @Override
  public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    super.deregister(ctx, promise);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
      SocketAddress localAddress, ChannelPromise promise) throws Exception {
    super.connect(ctx, remoteAddress, localAddress, promise);
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    // make sure when other handler's are added, the OutboundDelegatingHandler need to be
    // the last handler in outbound handlers
    if (ctx.pipeline().first() != this) {
      ctx.pipeline().addFirst(this);
      ctx.pipeline().remove(this);
    }
  }
}
