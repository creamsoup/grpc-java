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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;

/**
 * A InboundDelegatingHandler is a {@link io.netty.channel.ChannelHandler} installed in a internal
 * Channel in {@link DelegatingChannel.DelegatingPipeline} delegates inbound messages and events
 * from the internal channel to {@link DelegatingChannel}.
 *
 * <p>LifeCycle related events are selectively delegated to {@link DelegatingChannel}. When the
 * {@link DelegatingChannel} is transitioning to new underlying channel, life cycle events such as
 * channel registered, active, inactive and unregistered events are not trigger pipeline events
 * because life cycle of the DelegatingChannel didn't changed.
 *
 * delegates writes and events of the pipeline to internal channel in DelegatingChannel.
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
final class InboundDelegatingHandler implements ChannelInboundHandler {

  private final DelegatingChannel delegate;
  private final boolean expectChannelSwap;

  InboundDelegatingHandler(DelegatingChannel delegate, boolean expectChannelSwap) {
    this.delegate = delegate;
    this.expectChannelSwap = expectChannelSwap;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    if (expectChannelSwap) {
      delegate.setRegistered(true);
      delegate.pipeline().fireChannelRegistered();
    }
  }

  @Override
  public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
    boolean pspFailed = expectChannelSwap && !delegate.canSwap();
    boolean secondaryChannel = !expectChannelSwap;
    if (pspFailed || secondaryChannel) {
      delegate.setRegistered(false);
      delegate.pipeline().fireChannelUnregistered();
    }
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    if (expectChannelSwap) {
      delegate.setActive(true);
      delegate.pipeline().fireChannelActive();
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    boolean pspFailed = expectChannelSwap && !delegate.canSwap();
    boolean secondaryChannel = !expectChannelSwap;
    if (pspFailed || secondaryChannel) {
      delegate.setActive(false);
      delegate.pipeline().fireChannelInactive();
      delegate.pipeline().fireUserEventTriggered(new DelegatingChannel.InternalCloseEvent());
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    delegate.pipeline().fireChannelRead(msg);
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
    delegate.pipeline().fireChannelReadComplete();
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    delegate.pipeline().fireUserEventTriggered(evt);
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    delegate.pipeline().fireChannelWritabilityChanged();
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    // NOOP
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    // NOOP
  }

  @Override
  @Deprecated
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    delegate.pipeline().fireExceptionCaught(cause);
  }
}
