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

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.net.InetSocketAddress;

@ExperimentalApi("TODO")
class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  private LbPolicyConfiguration lbPolicyConfiguration;
  private AsyncCachingRlsClient routeLookupClient;
  private Subchannel rlsServerChannel;
  private AdaptiveThrottler throttler = AdaptiveThrottler.builder().build();
  private RlsPicker picker;

  RlsLoadBalancer(Helper helper) {
    System.out.println("creating new RLSLB");
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    System.out.println("handle: " + resolvedAddresses);
    LbPolicyConfiguration lbPolicyConfiguration =
        (LbPolicyConfiguration) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbPolicyConfiguration != null
        && !lbPolicyConfiguration.equals(this.lbPolicyConfiguration)) {
      applyServiceConfig(lbPolicyConfiguration);
    }
  }

  private void applyServiceConfig(final LbPolicyConfiguration lbPolicyConfiguration) {
    final RouteLookupConfig rlsConfig = lbPolicyConfiguration.getRouteLookupConfig();
    System.out.println("apply rlsConfig: " + rlsConfig);
    boolean needToConnect = this.lbPolicyConfiguration == null
        || !this.lbPolicyConfiguration.getRouteLookupConfig().getLookupService().equals(
        lbPolicyConfiguration.getRouteLookupConfig().getLookupService());
    if (needToConnect) {
      System.out.println("need to create new RlsClient");
      if (routeLookupClient != null) {
        routeLookupClient.close();
      }
      if (rlsServerChannel != null) {
        rlsServerChannel.shutdown();
      }

      EquivalentAddressGroup eag = RlsUtil.createEag(rlsConfig.getLookupService());
      System.out.println("rlsServer: " + rlsConfig.getLookupService() + " eag: " + eag);
      rlsServerChannel = helper.createSubchannel(
          CreateSubchannelArgs.newBuilder()
              .setAddresses(eag)
              .build());
      throttler = AdaptiveThrottler.builder().build();
      final AsyncCachingRlsClient client =
          AsyncCachingRlsClient.newBuilder()
              .setSubchannel(rlsServerChannel)
              .setScheduledExecutorService(helper.getScheduledExecutorService())
              .setExecutor(helper.getSynchronizationContext())
              .setMaxAgeMillis(rlsConfig.getMaxAgeInMillis())
              .setStaleAgeMillis(rlsConfig.getStaleAgeInMillis())
              .setMaxCacheSizeBytes(rlsConfig.getCacheSizeBytes())
              .setCallTimeoutMillis(rlsConfig.getLookupServiceTimeoutInMillis())
              .setThrottler(throttler)
              .setHelper(helper)
              .setLbPolicyConfig(lbPolicyConfiguration)
              .build();
      routeLookupClient = client;
      picker = new RlsPicker(lbPolicyConfiguration, client, helper);
      helper.updateBalancingState(ConnectivityState.CONNECTING, picker);
    }

    this.lbPolicyConfiguration = lbPolicyConfiguration;
    helper.getChannelLogger()
        .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
  }

  @Override
  public void requestConnection() {
    if (rlsServerChannel != null) {
      rlsServerChannel.requestConnection();
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    System.out.println("!! resolution error");
  }

  @Override
  public void shutdown() {
    if (rlsServerChannel != null) {
      rlsServerChannel.shutdown();
    }
    if (routeLookupClient != null) {
      routeLookupClient.close();
    }
  }
}
