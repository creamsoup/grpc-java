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

import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.util.List;

@ExperimentalApi("TODO")
class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  private LbPolicyConfiguration lbPolicyConfiguration;
  private RouteLookupClient routeLookupClient; // this will be probably only used in the picker
  private RlsPicker picker;
  private ManagedChannel oobChannel;
  private AdaptiveThrottler throttler = AdaptiveThrottler.builder().build();

  RlsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    ConfigOrError lbConfigOrError =
        (ConfigOrError) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbConfigOrError.getError() != null) {
      //TODO(creamsoup) clarify with mark, probably using existing?
      handleNameResolutionError(lbConfigOrError.getError());
      return;
    }
    LbPolicyConfiguration lbPolicyConfiguration =
        (LbPolicyConfiguration) lbConfigOrError.getConfig();
    if (!lbPolicyConfiguration.equals(this.lbPolicyConfiguration)) {
      applyServiceConfig(resolvedAddresses.getAddresses(), lbPolicyConfiguration);
    }
  }

  private void applyServiceConfig(
      List<EquivalentAddressGroup> addresses, LbPolicyConfiguration lbPolicyConfiguration) {
    checkArgument(!addresses.isEmpty(), "Requires at least one address for rls target");

    RouteLookupConfig rlsConfig = lbPolicyConfiguration.getRouteLookupConfig();
    if (!this.lbPolicyConfiguration.getRouteLookupConfig().getLookupService().equals(
        lbPolicyConfiguration.getRouteLookupConfig().getLookupService())) {
      if (oobChannel != null) {
        oobChannel.shutdown();
      }
      //TODO how to inherit channel creds from parent channel?
      oobChannel = helper.createOobChannel(addresses.get(0), rlsConfig.getLookupService());
      throttler = AdaptiveThrottler.builder().build();
    }
    RouteLookupClient client =
        RouteLookupClientImpl.builder()
            .setChannel(oobChannel)
            .setThrottler(throttler)
            //TODO just pass bigger object
            .setCallTimeoutMillis(rlsConfig.getLookupServiceTimeoutInMillis())
            .setTarget(rlsConfig.getLookupService())
            .setMaxCacheSize(rlsConfig.getCacheSize())
            .setMaxAgeMillis(rlsConfig.getMaxAgeInMillis())
            .setStaleAgeMillis(rlsConfig.getStaleAgeInMillis())
            .build();
    routeLookupClient.shutdown();
    routeLookupClient = client;
    this.lbPolicyConfiguration = lbPolicyConfiguration;
    helper.getChannelLogger()
        .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
    // rls picker will maintain connectivity status
    // TODO make sure some states are inherited from existing
    picker = new RlsPicker(lbPolicyConfiguration, client, helper);
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
      routeLookupClient.shutdown();
    }
    if (lbPolicyConfiguration != null) {
      lbPolicyConfiguration.cleanup();
    }
  }
}
