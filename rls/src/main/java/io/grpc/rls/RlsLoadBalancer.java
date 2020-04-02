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
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.rls.internal.AdaptiveThrottler;
import io.grpc.rls.internal.AsyncCachingRlsClient;
import io.grpc.rls.internal.ChildLbResolvedAddressFactory;
import io.grpc.rls.internal.LbPolicyConfiguration;
import io.grpc.rls.internal.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Implementation of {@link LoadBalancer} backed by route lookup service.
 */
final class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  @Nullable
  private LbPolicyConfiguration lbPolicyConfiguration;
  @Nullable
  private AsyncCachingRlsClient routeLookupClient;
  @Nullable
  private ManagedChannel rlsServerChannel;

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
        if (rlsServerChannel != null) {
          rlsServerChannel.shutdown();
        }

        rlsServerChannel = helper.createResolvingOobChannel(rlsConfig.getLookupService());
        AdaptiveThrottler throttler = AdaptiveThrottler.builder().build();
        ChildLbResolvedAddressFactory childLbResolvedAddressFactory =
            new ChildLbResolvedAddressFactory(
                resolvedAddresses.getAddresses(), resolvedAddresses.getAttributes());
        AsyncCachingRlsClient.Builder rlsClientBuilder = AsyncCachingRlsClient.newBuilder()
            .setChildLbResolvedAddressesFactory(childLbResolvedAddressFactory)
            .setChannel(rlsServerChannel)
            .setMaxAgeNanos(TimeUnit.MILLISECONDS.toNanos(rlsConfig.getMaxAgeInMillis()))
            .setStaleAgeNanos(TimeUnit.MILLISECONDS.toNanos(rlsConfig.getStaleAgeInMillis()))
            .setCallTimeoutNanos(
                TimeUnit.MILLISECONDS.toNanos(rlsConfig.getLookupServiceTimeoutInMillis()))
            .setMaxCacheSizeBytes(rlsConfig.getCacheSizeBytes())
            .setThrottler(throttler)
            .setHelper(helper)
            .setLbPolicyConfig(lbPolicyConfiguration);
        if (rlsConfig.getRequestProcessingStrategy()
            == RequestProcessingStrategy.SYNC_LOOKUP_CLIENT_SEES_ERROR) {
          rlsClientBuilder.refreshBackoffEntries();
        }
        routeLookupClient = rlsClientBuilder.build();
      }
      // TODO(creamsoup) update configs if necessary, for v1 implementation this is not required.
      this.lbPolicyConfiguration = lbPolicyConfiguration;
      helper.getChannelLogger()
          .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
    }
  }

  @Override
  public void requestConnection() {
    if (rlsServerChannel != null) {
      rlsServerChannel.getState(true);
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    //TODO tell childLb with update lb state error
    System.out.println("!! resolution error" + error);
//    helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, rlsPicker);
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
