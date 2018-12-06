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

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketAddress;

/**
 * DelegatingChannel is a {@link Channel} that delegates IO to an arbitrary channel. On top of
 * {@link Channel netty channel}'s functionality, it listens a swap event ({@link
 * PspNegotiationCompletedEvent} and {@link PspNegotiationFailedEvent}. The {@link
 * PspNegotiationCompletedEvent} swaps underlying channel.
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
//TODO handle addLast to keep the DOH as last handler. (may need to use unsafe)
//TODO: delegate is overloaded here and cause a lot of confusion consider different term
//TODO: verify all promises and future (which channel makes more sense)
//TODO: finish unimplemented methods
//TODO: work with security team if this looks okay
//TODO: streamz metric?!
//TODO: more doc & comments
//TODO: test
@SuppressWarnings("FutureReturnValueIgnored")
public final class DelegatingChannel implements Channel {

  private final CloseFuture closeFuture = new CloseFuture(this);
  private final ChannelId id = DefaultChannelId.newInstance();

  private final DelegatingUnsafe unsafe;
  private final ChannelPipeline pipeline;
  private final ChannelSwapHandler channelSwapHandler;
  private final Class<? extends Channel> channelType;

  private Channel delegate;
  private boolean allowSwap;
  private boolean registered = false;
  private boolean active = false;
  private boolean closed = false;

  public DelegatingChannel() {
    this(
        NioSocketChannel.class,
        new ChannelSwapListener() {
          @Override
          public void onCheckCondition() {
            System.out.println("onCheckCondition");
          }

          @Override
          public void onOldClosed(Channel oldChannel) {
            System.out.println("onOldClosed");
          }

          @Override
          public void onNewChannelInitialized(
              PspNegotiationCompletedEvent event, Channel oldChannel, Channel newChannel) {
            System.out.println("onNewChannelInitialized");
          }

          @Override
          public void onSwapCompletion() {
            System.out.println("onSwapCompletion");
          }

          @Override
          public void onFail(Throwable cause) {
            System.out.println("onFail");
          }
        },
        false);
  }

  public DelegatingChannel(
      Class<? extends Channel> channelType, ChannelSwapListener swapListener, boolean allowSwap) {
    this.channelType = channelType;
    this.channelSwapHandler = new ChannelSwapHandler(swapListener);
    // Connects DelegatingChannel's pipeline and underlying channel.
    // NOTE: underlying channel's pipeline doesn't expect any other handlers, and all handlers
    //      should be installed in the DelegatingChannel.
    // Underlying channel reads and passes the remote msg to DelegatingChannel's pipeline.
    delegate = createNewInnerChannel(true);
    // delegate.pipeline().addFirst(new InboundDelegatingHandler(this, true));
    // Write from the DelegateChannel's pipeline passed down to underlying channel via
    // InboundDelegatingHandler which is installed when newChannelPipeline is created
    // which will eventually sent to the remote.
    unsafe = newUnsafe();
    pipeline = newChannelPipeline();
    this.allowSwap = allowSwap;
  }

  public boolean canSwap() {
    return allowSwap;
  }

  @Override
  public ChannelId id() {
    return id;
  }

  @Override
  public Unsafe unsafe() {
    return unsafe;
  }

  private DelegatingUnsafe newUnsafe() {
    return new DelegatingUnsafe(this);
  }

  @Override
  public ChannelPipeline pipeline() {
    return pipeline;
  }

  private ChannelPipeline newChannelPipeline() {
    return new DelegatingPipeline(this);
  }

  /** NOTE: do not store this value. */
  Channel underlyingChannel() {
    return delegate;
  }

  @Override
  public Channel parent() {
    return null;
  }

  @Override
  public boolean isRegistered() {
    return registered;
  }

  void setRegistered(boolean registered) {
    this.registered = registered;
  }

  void setActive(boolean active) {
    this.active = active;
  }

  @Override
  public boolean isActive() {
    return active;
  }

  @Override
  public ChannelFuture closeFuture() {
    return closeFuture;
  }

  @Override
  public ChannelFuture close() {
    doClose();
    return closeFuture;
  }

  @Override
  public ChannelFuture close(ChannelPromise promise) {
    ChannelFuture closeFuture =
        pipeline.close(DelegatingChannelPromise.of(underlyingChannel(), promise));
    doClose();
    return closeFuture;
  }

  private void doClose() {
    if (closed) {
      return;
    }
    closed = true;
    closeFuture.setClosed();
    delegate.close();
    pipeline().close();
  }

  // Pure delegate to supervised channel

  @Override
  public EventLoop eventLoop() {
    return delegate.eventLoop();
  }

  @Override
  public ChannelConfig config() {
    return delegate.config();
  }

  @Override
  public boolean isOpen() {
    return delegate.isOpen();
  }

  @Override
  public ChannelMetadata metadata() {
    return delegate.metadata();
  }

  @Override
  public SocketAddress localAddress() {
    return delegate.localAddress();
  }

  @Override
  public SocketAddress remoteAddress() {
    return delegate.remoteAddress();
  }

  @Override
  public boolean isWritable() {
    return delegate.isWritable();
  }

  @Override
  public long bytesBeforeUnwritable() {
    return delegate.bytesBeforeUnwritable();
  }

  @Override
  public long bytesBeforeWritable() {
    return delegate.bytesBeforeWritable();
  }

  @Override
  public ByteBufAllocator alloc() {
    return delegate.alloc();
  }

  @Override
  public <T> Attribute<T> attr(AttributeKey<T> key) {
    return delegate.attr(key);
  }

  @Override
  public <T> boolean hasAttr(AttributeKey<T> key) {
    return delegate.hasAttr(key);
  }

  // Delegate to pipeline

  @Override
  public ChannelFuture bind(SocketAddress localAddress) {
    return pipeline().bind(localAddress);
  }

  @Override
  public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
    return pipeline().bind(localAddress, promise);
  }

  @Override
  public ChannelFuture connect(SocketAddress remoteAddress) {
    return pipeline().connect(remoteAddress);
  }

  @Override
  public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
    return pipeline().connect(remoteAddress, localAddress);
  }

  @Override
  public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
    return pipeline().connect(remoteAddress, promise);
  }

  @Override
  public ChannelFuture connect(
      SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
    return pipeline().connect(remoteAddress, localAddress, promise);
  }

  @Override
  public ChannelFuture deregister() {
    return pipeline().deregister();
  }

  @Override
  public ChannelFuture deregister(ChannelPromise promise) {
    return pipeline().deregister(promise);
  }

  @Override
  public ChannelFuture disconnect() {
    return pipeline().disconnect();
  }

  @Override
  public ChannelFuture disconnect(ChannelPromise promise) {
    return pipeline().disconnect(promise);
  }

  @Override
  public Channel read() {
    delegate.pipeline().read();
    return this;
  }

  @Override
  public ChannelFuture write(Object msg) {
    return pipeline().write(msg);
  }

  @Override
  public ChannelFuture write(Object msg, ChannelPromise promise) {
    return pipeline().write(msg, promise);
  }

  @Override
  public Channel flush() {
    pipeline().flush();
    return this;
  }

  @Override
  public ChannelFuture writeAndFlush(Object msg) {
    return pipeline().writeAndFlush(msg);
  }

  @Override
  public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
    return pipeline().writeAndFlush(msg, DelegatingChannelPromise.of(this, promise));
  }

  @Override
  public ChannelPromise newPromise() {
    return pipeline().newPromise();
  }

  @Override
  public ChannelProgressivePromise newProgressivePromise() {
    return pipeline().newProgressivePromise();
  }

  @Override
  public ChannelFuture newSucceededFuture() {
    return pipeline().newSucceededFuture();
  }

  @Override
  public ChannelFuture newFailedFuture(Throwable cause) {
    return pipeline().newFailedFuture(cause);
  }

  @Override
  public ChannelPromise voidPromise() {
    return pipeline().voidPromise();
  }

  @Override
  public int compareTo(Channel o) {
    if (this == o) {
      return 0;
    }

    return id().compareTo(o.id());
  }

  private Channel createNewInnerChannel(boolean primary) {
    Channel channel;
    try {
      channel = channelType.getConstructor().newInstance();
      channel.pipeline()
          .addLast(new InboundDelegatingHandler(this, primary));
      return channel;
    } catch (InstantiationException e) {
      //TODO do better than this
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  public interface ChannelSwapListener {

    void onCheckCondition();

    void onOldClosed(Channel oldChannel);

    void onNewChannelInitialized(
        PspNegotiationCompletedEvent event, Channel oldChannel, Channel newChannel);

    void onSwapCompletion();

    void onFail(Throwable cause);
  }

  final class ChannelSwapHandler {

    private ChannelSwapListener listener;

    public ChannelSwapHandler(
        ChannelSwapListener listener) {
      this.listener = listener;
    }

    public final void swap(PspNegotiationCompletedEvent event) {
      try {
        doCheckCondition();
        allowSwap = false;
        final Channel oldChannel = delegate;
        final Channel newChannel = createNewInnerChannel(false);

        listener.onNewChannelInitialized(event, oldChannel, newChannel);

        ChannelPromise registerPromise = newChannel.newPromise();
        newChannel.unsafe().register(eventLoop(), registerPromise);
        final ChannelPromise connectPromise = newChannel.newPromise();
        registerPromise.addListener(new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
              newChannel.unsafe().connect(oldChannel.remoteAddress(), null, connectPromise);
            } else if (future.isCancelled()) {
              connectPromise.cancel(true);
            } else {
              ChannelSwapHandler.this.handleFail(future.cause());
            }
          }
        });
        connectPromise.addListener(new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
              delegate = newChannel;

              // Disconnect inbound delegation to prevent accidentally send msg
              // / event / etc.
              oldChannel.pipeline()
                  .remove(InboundDelegatingHandler.class);
              oldChannel.unsafe()
                  .deregister(
                      oldChannel.newPromise()
                          .addListener(
                              ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));

              oldChannel.unsafe().closeForcibly();
              listener.onOldClosed(oldChannel);
              listener.onSwapCompletion();
            } else if (future.isCancelled()) {
              connectPromise.cancel(true);
            } else {
              ChannelSwapHandler.this.handleFail(future.cause());
            }
          }
        });
      } catch (Exception e) {
        handleFail(e);
      }
    }

    private void doCheckCondition() {
      Preconditions.checkState(allowSwap, "Internal channel can be swapped at most once");
      listener.onCheckCondition();
    }

    public void handleFail(Throwable e) {
      listener.onFail(e);
    }

    public final void disallowSwap(PspNegotiationFailedEvent event) {
      System.out.println("DISALLOW SWAP");
      allowSwap = false;
      doCleanup(event);
    }

    protected void doCleanup(PspNegotiationFailedEvent event) {}
  }

  private final class DelegatingPipeline extends DefaultChannelPipeline {

    private boolean closing = false;

    public DelegatingPipeline(DelegatingChannel channel) {
      super(channel);
      this.addLast("delegate",
          new OutboundDelegatingHandler());
    }

    @Override
    protected void onUnhandledInboundUserEventTriggered(Object evt) {
      System.out.println("event: " + evt);
      if (evt instanceof PspNegotiationCompletedEvent) {
        channelSwapHandler.swap((PspNegotiationCompletedEvent) evt);
        return;
      } else if (evt instanceof PspNegotiationFailedEvent) {
        channelSwapHandler.disallowSwap((PspNegotiationFailedEvent) evt);
        return;
      } else if (evt instanceof InternalCloseEvent) {
        if (!closing) {
          closing = true;
          if (active) {
            this.fireChannelInactive();
          }
          if (registered) {
            this.fireChannelRegistered();
          }
          eventLoop().execute(new Runnable() {
            @Override
            public void run() {
              DelegatingChannel.this.close();
            }
          });
        }
        return;
      }
      super.onUnhandledInboundUserEventTriggered(evt);
    }

    @Override
    protected void onUnhandledInboundException(Throwable cause) {
      if (!active) {
        return;
      }
      super.onUnhandledInboundException(cause);
    }
  }

  // copied from AbstractChannel
  static final class CloseFuture extends DefaultChannelPromise {

    CloseFuture(Channel ch) {
      super(ch);
    }

    @Override
    public ChannelPromise setSuccess() {
      throw new IllegalStateException();
    }

    @Override
    public ChannelPromise setFailure(Throwable cause) {
      throw new IllegalStateException();
    }

    @Override
    public boolean trySuccess() {
      throw new IllegalStateException();
    }

    @Override
    public boolean tryFailure(Throwable cause) {
      throw new IllegalStateException();
    }

    boolean setClosed() {
      return super.trySuccess();
    }
  }

  /** An event to close both internal / delegating channel. */
  static final class InternalCloseEvent {}
}
