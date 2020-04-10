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
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.rls.internal.AdaptiveThrottler;
import io.grpc.rls.internal.CachingRlsLbClient;
import io.grpc.rls.internal.ChildLbResolvedAddressFactory;
import io.grpc.rls.internal.LbPolicyConfiguration;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import javax.annotation.Nullable;

/**
 * Implementation of {@link LoadBalancer} backed by route lookup service.
 */
final class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  @Nullable
  private LbPolicyConfiguration lbPolicyConfiguration;
  @Nullable
  private CachingRlsLbClient routeLookupClient;

  RlsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    LbPolicyConfiguration lbPolicyConfiguration =
        (LbPolicyConfiguration) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbPolicyConfiguration != null
        && !lbPolicyConfiguration.equals(this.lbPolicyConfiguration)) {
      final RouteLookupConfig rlsConfig = lbPolicyConfiguration.getRouteLookupConfig();
      boolean needToConnect = this.lbPolicyConfiguration == null
          || !this.lbPolicyConfiguration.getRouteLookupConfig().getLookupService().equals(
          lbPolicyConfiguration.getRouteLookupConfig().getLookupService());
      if (needToConnect) {
        if (routeLookupClient != null) {
          routeLookupClient.close();
        }

        //TODO rename the rls client
        routeLookupClient = CachingRlsLbClient.newBuilder()
            .setHelper(helper)
            .setRlsConfig(rlsConfig)
            .setLbPolicyConfig(lbPolicyConfiguration)
            .setThrottler(AdaptiveThrottler.builder().build())
            .setChildLbResolvedAddressesFactory(
                new ChildLbResolvedAddressFactory(
                    resolvedAddresses.getAddresses(), resolvedAddresses.getAttributes()))
            .build();
      }
      // TODO(creamsoup) update configs if necessary, for v1 implementation this is not required.
      this.lbPolicyConfiguration = lbPolicyConfiguration;
      helper.getChannelLogger()
          .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
    }
  }

  @Override
  public void requestConnection() {
    routeLookupClient.requestConnection();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    //TODO tell childLb with update lb state error
    System.out.println("!! resolution error" + error);
    // helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, rlsPicker);
  }

  @Override
  public void shutdown() {
    if (routeLookupClient != null) {
      routeLookupClient.close();
    }
  }
}
