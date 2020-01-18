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
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LbPolicyConfiguration.LoadBalancingPolicy;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.RouteLookupClient.RouteLookupInfo;
import io.grpc.rls.Throttler.ThrottledException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class RlsPicker extends SubchannelPicker {

  private LbPolicyConfiguration lbPolicyConfiguration;
  private RouteLookupClientImpl rlsClient; // cache is embedded
  private RlsRequestFactory requestFactory; // aka keyBuilderMap
  // not specified in the design doc.
  private Helper helper;
  private RequestProcessingStrategy strategy;
  private State state = State.OKAY;
  private Status error = null;


  // TODO manage connectivity status

  public RlsPicker(
      LbPolicyConfiguration lbPolicyConfiguration, RouteLookupClientImpl client, Helper helper) {
    this.lbPolicyConfiguration = lbPolicyConfiguration;
    this.rlsClient = client;
    this.helper = helper;
    this.requestFactory = new RlsRequestFactory(lbPolicyConfiguration.getRouteLookupConfig());
    this.strategy = lbPolicyConfiguration.getRouteLookupConfig().getRequestProcessingStrategy();
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    if (state == State.ERROR) {
      return PickResult.withError(error);
    } else if (state == State.TRANSIENT_ERROR) {
      return PickResult.withNoResult();
    }

    String target = args.getMethodDescriptor().getServiceName();
    String path = args.getMethodDescriptor().getFullMethodName();

    RouteLookupRequest request = requestFactory.create(target, path, args.getHeaders());
    RouteLookupInfo response = rlsClient.routeLookup(request, helper);

    if (response.isDone()) {
      // TODO: handle refCount childPolicy
      return processCacheHit(response, args);
    }
    return handlePendingRequest(response, args);
  }

  private PickResult processCacheHit(RouteLookupInfo lookupInfo, PickSubchannelArgs args) {
    RouteLookupResponse response;
    try {
      response = lookupInfo.get();
      // TODO are those errors are possible? if not remove handle error, also make sure this is true
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return handleError(e);
    } catch (ExecutionException e) {
      return handleError(e.getCause());
    }
    // cache hit
    ChildPolicyWrapper childPolicyWrapper = lookupInfo.getChildPolicyWrapper();
    return childPolicyWrapper.getPicker().pickSubchannel(args);
  }

  private PickResult handlePendingRequest(
      final RouteLookupInfo response, final PickSubchannelArgs args) {
    response.addListener(new Runnable() {
      @Override
      public void run() {
        if (response.isDone() && !response.isCancelled()) {
          try {
            final RouteLookupResponse rlsResponse = response.get();
            LoadBalancingPolicy loadBalancingPolicy =
                lbPolicyConfiguration.getLoadBalancingPolicy();
            Map<String, ?> childLbPolicyConfig =
                loadBalancingPolicy.getEffectiveChildPolicy(rlsResponse.getTarget());

            // todo maybe wrap the helper (see design doc helper section)
            Helper childHelper = helper;
            final LoadBalancer childLb =
                loadBalancingPolicy.getEffectiveLbProvider().newLoadBalancer(childHelper);
            ConfigOrError parsedChildLbPolicy =
                loadBalancingPolicy.getEffectiveLbProvider()
                    .parseLoadBalancingPolicyConfig(childLbPolicyConfig);
            checkState(
                parsedChildLbPolicy.getError() == null,
                "invalid child policy: %s",
                childLbPolicyConfig);
            childLb.handleResolvedAddresses(
                ResolvedAddresses.newBuilder()
                    .setLoadBalancingPolicyConfig(parsedChildLbPolicy.getConfig())
                    .setAttributes(Attributes.newBuilder()
                        .set(ATTR_LOAD_BALANCING_CONFIG, childLbPolicyConfig)
                        .build())
                    .build());
            childLb.requestConnection();
            final Subchannel subChannel =
                helper.createSubchannel(CreateSubchannelArgs.newBuilder().build());
            final ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper().acquire();
            childPolicyWrapper.setPicker(new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                String headerData = rlsResponse.getHeaderData();
                //TODO fixme
                // while (iter.hasNext()) {
                //   String key = iter.next();
                //   String val = iter.next();
                //   args.getHeaders()
                //       .put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), val);
                // }
                return PickResult.withSubchannel(subChannel);
              }
            });

            subChannel.start(new SubchannelStateListener() {
              @Override
              public void onSubchannelState(ConnectivityStateInfo newState) {
                childPolicyWrapper.setConnectivityState(newState.getState());
                if (newState.getState() == ConnectivityState.TRANSIENT_FAILURE
                    || newState.getState() == ConnectivityState.SHUTDOWN) {
                  // handle subchannel shutdown
                  childPolicyWrapper.release();
                }
              }
            });
            subChannel.requestConnection();

            childPolicyWrapper.setChildPolicy(loadBalancingPolicy);
            childPolicyWrapper.setConnectivityState(ConnectivityState.CONNECTING);
          } catch (InterruptedException e) {
            e.printStackTrace();
          } catch (ExecutionException e) {
            // in the exception case, the cache will automatically remove the entry from cache to
            // not serve failed event
            // TODO in this exception, we should handle how to proceed. withError? noResult?
            e.printStackTrace();
          }
        }
      }
    }, helper.getSynchronizationContext());
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

  private PickResult handleError(Throwable cause) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        if (cause instanceof ThrottledException) {
          return PickResult.withError(Status.RESOURCE_EXHAUSTED.withCause(cause));
        }
        return PickResult.withError(Status.UNKNOWN.withCause(cause));
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        // fall-through
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        return useFallbackSubchannel();
    }
    throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
  }

  private Subchannel fallbackSubchannel;
  private String fallbackAddress;

  /** Uses Subchannel connected to default target. */
  private PickResult useFallbackSubchannel() {
    String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    if (!defaultTarget.equals(fallbackAddress)) {
      if (fallbackSubchannel != null) {
        fallbackSubchannel.shutdown();
      }
      fallbackAddress = defaultTarget;
      String[] addrs = defaultTarget.split(":", 2);
      checkState(addrs.length == 2, "address expect to be host:port format");
      String host = addrs[0];
      int port = Integer.parseInt(addrs[1]);
      InetSocketAddress targetSocketAddr = InetSocketAddress.createUnresolved(host, port);
      fallbackSubchannel = helper.createSubchannel(
          CreateSubchannelArgs.newBuilder()
              .setAddresses(new EquivalentAddressGroup(targetSocketAddr))
              .build());
    }
    return PickResult.withSubchannel(fallbackSubchannel);
  }

  public void propagateError(Status error) {
    checkNotNull(error, "error");
    if (error.isOk()) {
      state = State.TRANSIENT_ERROR;
    } else {
      state = State.ERROR;
      this.error = error;
    }
  }

  private enum State {
    OKAY, TRANSIENT_ERROR, ERROR
  }
}
