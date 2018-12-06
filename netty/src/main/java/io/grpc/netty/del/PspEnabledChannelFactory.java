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

import io.grpc.netty.del.DelegatingChannel.ChannelSwapListener;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;

public class PspEnabledChannelFactory implements ChannelFactory<DelegatingChannel> {

  private final Class<? extends Channel> channelType;
  private final ChannelSwapListener swapListener;

  //TODO: potentially pass the ProdChannelBuilder
  public PspEnabledChannelFactory(Class<? extends Channel> channelType,
      ChannelSwapListener swapListener) {
    this.channelType = channelType;
    this.swapListener = swapListener;
  }

  @Override
  public DelegatingChannel newChannel() {
    return new DelegatingChannel(channelType, swapListener, true);
  }
}
