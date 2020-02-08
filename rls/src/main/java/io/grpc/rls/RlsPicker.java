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

import static com.google.common.base.Preconditions.checkState;
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.rls.AsyncCachingRlsClient.CachedResponse;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LbPolicyConfiguration.LoadBalancingPolicy;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.net.InetSocketAddress;
import java.util.Map;

public class RlsPicker extends SubchannelPicker {

  /** A header will be added when RLS server respond with additional header data. */
  public static final Metadata.Key<String> RLS_DATA_KEY =
      Metadata.Key.of("X-Google-RLS-Data", Metadata.ASCII_STRING_MARSHALLER);

  private LbPolicyConfiguration lbPolicyConfiguration;
  private AsyncCachingRlsClient rlsClient; // cache is embedded
  private RlsRequestFactory requestFactory; // aka keyBuilderMap

  // not specified in the design doc.
  private Helper helper;
  private RequestProcessingStrategy strategy;


  // TODO manage connectivity status

  public RlsPicker(
      LbPolicyConfiguration lbPolicyConfiguration, AsyncCachingRlsClient client, Helper helper) {
    this.lbPolicyConfiguration = lbPolicyConfiguration;
    this.rlsClient = client;
    this.helper = helper;
    this.requestFactory = new RlsRequestFactory(lbPolicyConfiguration.getRouteLookupConfig());
    this.strategy = lbPolicyConfiguration.getRouteLookupConfig().getRequestProcessingStrategy();
    System.out.println("rls picker created");
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    System.out.println("pick subchannel : " + args);
    //TODO somehow get the authority
//    String target = args.getCallOptions().getAuthority();
    String target = "localhost";
    String path = args.getMethodDescriptor().getFullMethodName();

    RouteLookupRequest request = requestFactory.create(target, path, args.getHeaders());
    System.out.println("request: " + request);
    final CachedResponse response = rlsClient.get(request);
    System.out.println("response: " + response);

    if (response.hasValidData()) {
      System.out.println("woohoo has valid data!");
      ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
      return childPolicyWrapper.getPicker().pickSubchannel(args);
    } else if (response.hasError()) {
      System.out.println("error");
      return handleError(response.getStatus());
    } else {
      System.out.println("pending");
      // pending request
      return handlePendingRequest(args);
    }
  }

  private PickResult handleError(Status cause) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        return PickResult.withError(cause);
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        // fall-through
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        return useFallbackSubchannel();
    }
    throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
  }

  private PickResult handlePendingRequest(PickSubchannelArgs args) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        // fall-through
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        // pick queue
        return PickResult.withNoResult();
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        // use default target
        return useFallbackSubchannel();
    }
    throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
  }

  private Subchannel fallbackSubchannel;
  private String fallbackAddress;
  private ConnectivityState fallbackChannelConnectivityState = ConnectivityState.IDLE;
  private Status fallbackChannelStatus;

  /** Uses Subchannel connected to default target. */
  private PickResult useFallbackSubchannel() {
    String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    if (!defaultTarget.equals(fallbackAddress)) {
      if (fallbackSubchannel != null) {
        fallbackSubchannel.shutdown();
      }
      fallbackAddress = defaultTarget;
      InetSocketAddress targetSocketAddr = parseAddress(defaultTarget);
      fallbackSubchannel = helper.createSubchannel(
          CreateSubchannelArgs.newBuilder()
              .setAddresses(new EquivalentAddressGroup(targetSocketAddr))
              .build());
      fallbackSubchannel.start(new SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          fallbackChannelConnectivityState = newState.getState();
          fallbackChannelStatus = newState.getStatus();
        }
      });
    }
    switch (fallbackChannelConnectivityState) {
      case IDLE:
        // fall-through
      case CONNECTING:
        return PickResult.withNoResult();
      case TRANSIENT_FAILURE:
        // fall-through
      case SHUTDOWN:
        return PickResult
            .withError(fallbackChannelStatus != null ? fallbackChannelStatus : Status.UNKNOWN);
      case READY:
        return PickResult.withSubchannel(fallbackSubchannel);
    }
    throw new AssertionError();
  }

  private InetSocketAddress parseAddress(String name) {
    if (name.contains(":")) {
      String[] addrs = name.split(":", 2);
      checkState(addrs.length == 2, "address expect to be host:port format");
      String host = addrs[0];
      int port = Integer.parseInt(addrs[1]);
      return InetSocketAddress.createUnresolved(host, port);
    } else {
      return InetSocketAddress.createUnresolved(name, 80);
    }
  }

  public void propagateError(Status error) {
    //TODO impl
    System.out.println("error" + error);
  }
}
