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

import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.util.Collections;
import java.util.List;

@ExperimentalApi("TODO")
class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  private LbPolicyConfiguration lbPolicyConfiguration;
  private AsyncCachingRlsClient routeLookupClient;
  private Subchannel rlsServerChannel;

  RlsLoadBalancer(Helper helper) {
    System.out.println("creating new RLSLB");
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  @SuppressWarnings("deprecation")
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    System.out.println("RLSLB: handle subchannel state: " + stateInfo.getState());
    new Throwable().printStackTrace(System.out);
    super.handleSubchannelState(subchannel, stateInfo);
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    System.out.println("handle: " + resolvedAddresses);
    LbPolicyConfiguration lbPolicyConfiguration =
        (LbPolicyConfiguration) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbPolicyConfiguration != null
        && !lbPolicyConfiguration.equals(this.lbPolicyConfiguration)) {
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
        AdaptiveThrottler throttler = AdaptiveThrottler.builder().build();
        ChildLoadBalancerHelper childBalancerHelper = new ChildLoadBalancerHelper(helper);
        ChildLbResolvedAddressFactory childLbResolvedAddressFactory =
            new ChildLbResolvedAddressFactory(resolvedAddresses);
        final AsyncCachingRlsClient client =
            AsyncCachingRlsClient.newBuilder()
                .setChildLbResolvedAddressesFactory(
                    childLbResolvedAddressFactory)
                .setSubchannel(rlsServerChannel)
                .setScheduledExecutorService(helper.getScheduledExecutorService())
                .setExecutor(helper.getSynchronizationContext())
                .setMaxAgeMillis(rlsConfig.getMaxAgeInMillis())
                .setStaleAgeMillis(rlsConfig.getStaleAgeInMillis())
                .setMaxCacheSizeBytes(rlsConfig.getCacheSizeBytes())
                .setCallTimeoutMillis(rlsConfig.getLookupServiceTimeoutInMillis())
                .setThrottler(throttler)
                .setHelper(childBalancerHelper)
                .setLbPolicyConfig(lbPolicyConfiguration)
                .build();
        routeLookupClient = client;
        // rls picker will report to helper
        RlsPicker rlsPicker = new RlsPicker(lbPolicyConfiguration, client, helper, childLbResolvedAddressFactory);
        childBalancerHelper.setRlsPicker(rlsPicker);
      }
      // TODO(creamsoup) update configs if necessary (maybe easier to create new cache?)
      //  for v1 implementation this is not required.

      this.lbPolicyConfiguration = lbPolicyConfiguration;
      helper.getChannelLogger()
          .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
    }
  }

  @Override
  public void requestConnection() {
    if (rlsServerChannel != null) {
      rlsServerChannel.requestConnection();
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    System.out.println("!! resolution error" + error);
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

  public static class ChildLbResolvedAddressFactory {

    private final List<EquivalentAddressGroup> addresses;
    private final Attributes attributes;

    public ChildLbResolvedAddressFactory(ResolvedAddresses resolvedAddresses) {
      checkNotNull(resolvedAddresses, "resolvedAddresses");
      this.addresses = Collections.unmodifiableList(resolvedAddresses.getAddresses());
      this.attributes = resolvedAddresses.getAttributes();
    }

    public ResolvedAddresses create(Object childLbConfig) {
      return
          ResolvedAddresses.newBuilder()
              .setAddresses(addresses)
              .setAttributes(attributes)
              .setLoadBalancingPolicyConfig(childLbConfig)
              .build();
    }
  }
}
