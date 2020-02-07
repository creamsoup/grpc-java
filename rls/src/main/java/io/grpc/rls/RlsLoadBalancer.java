/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.sun.jndi.toolkit.url.Uri;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.net.MalformedURLException;
import java.util.List;

@ExperimentalApi("TODO")
class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  private LbPolicyConfiguration lbPolicyConfiguration;
  private AsyncCachingRlsClient routeLookupClient;
  private ManagedChannel oobChannel;
  private AdaptiveThrottler throttler = AdaptiveThrottler.builder().build();
  @VisibleForTesting
  RlsPicker picker;

  RlsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    LbPolicyConfiguration lbPolicyConfiguration =
        (LbPolicyConfiguration) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (!lbPolicyConfiguration.equals(this.lbPolicyConfiguration)) {
      applyServiceConfig(lbPolicyConfiguration);
    }
  }

  private void applyServiceConfig(LbPolicyConfiguration lbPolicyConfiguration) {
    RouteLookupConfig rlsConfig = lbPolicyConfiguration.getRouteLookupConfig();
    if (this.lbPolicyConfiguration == null
        || !this.lbPolicyConfiguration.getRouteLookupConfig().getLookupService().equals(
            lbPolicyConfiguration.getRouteLookupConfig().getLookupService())) {
      if (oobChannel != null) {
        oobChannel.shutdown();
      }
      //TODO authority should be same as the actual channel's authority
      // oobChannel = helper.createOobChannel(rlsConfig.getLookupService(), rlsConfig.getLookupService());
      oobChannel = NettyChannelBuilder.forTarget(rlsConfig.getLookupService()).overrideAuthority()
      throttler = AdaptiveThrottler.builder().build();
    }
    // only update the cache entry if the
    AsyncCachingRlsClient client =
        AsyncCachingRlsClient.newBuilder()
        .setChannel(oobChannel)
        .setScheduledExecutorService(helper.getScheduledExecutorService())
        .setExecutor(helper.getSynchronizationContext())
        .setMaxAgeMillis(rlsConfig.getMaxAgeInMillis())
        .setStaleAgeMillis(rlsConfig.getStaleAgeInMillis())
        .setMaxCacheSizeBytes(rlsConfig.getCacheSizeBytes())
        .setCallTimeoutMillis(rlsConfig.getLookupServiceTimeoutInMillis())
        .setThrottler(throttler)
        .build();
    if (routeLookupClient != null) {
      routeLookupClient.close();
    }
    routeLookupClient = client;
    this.lbPolicyConfiguration = lbPolicyConfiguration;
    helper.getChannelLogger()
        .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
    // rls picker will maintain connectivity status
    // TODO make sure some states are inherited from existing
    picker = new RlsPicker(lbPolicyConfiguration, client, helper);
    helper.updateBalancingState(oobChannel.getState(false), picker);
  }

  @Override
  public void requestConnection() {
    if (oobChannel != null) {
      oobChannel.getState(true);
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (picker != null) {
      picker.propagateError(error);
    }
  }

  @Override
  public void shutdown() {
    if (oobChannel != null) {
      oobChannel.shutdown();
    }
    if (routeLookupClient != null) {
      routeLookupClient.close();
    }
  }
}
