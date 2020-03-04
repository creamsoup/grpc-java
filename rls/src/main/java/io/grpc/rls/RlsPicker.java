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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.rls.AsyncCachingRlsClient.CachedResponse;
import io.grpc.rls.AsyncCachingRlsClient.ChildPolicyReportingHelper;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.RlsLoadBalancer.ChildLbResolvedAddressFactory;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class RlsPicker extends SubchannelPicker {

  /** A header will be added when RLS server respond with additional header data. */
  public static final Metadata.Key<String> RLS_DATA_KEY =
      Metadata.Key.of("X-Google-RLS-Data", Metadata.ASCII_STRING_MARSHALLER);

  private final ChildLbResolvedAddressFactory childLbResolvedAddressFactory;
  private LbPolicyConfiguration lbPolicyConfiguration;
  @Nullable
  private AsyncCachingRlsClient rlsClient; // cache is embedded
  private RlsRequestFactory requestFactory; // aka keyBuilderMap

  // not specified in the design doc.
  private Helper helper;
  private RequestProcessingStrategy strategy;

  private RlsSubchannelStateManager subchannelStateManager = new RlsSubchannelStateManager();

  RlsPicker(
      LbPolicyConfiguration lbPolicyConfiguration,
      AsyncCachingRlsClient rlsClient,
      Helper helper,
      ChildLbResolvedAddressFactory childLbResolvedAddressFactory) {
    this.lbPolicyConfiguration = lbPolicyConfiguration;
    this.rlsClient = rlsClient;
    this.helper = helper;
    this.requestFactory = new RlsRequestFactory(lbPolicyConfiguration.getRouteLookupConfig());
    this.strategy = lbPolicyConfiguration.getRouteLookupConfig().getRequestProcessingStrategy();
    this.childLbResolvedAddressFactory = childLbResolvedAddressFactory;
    helper.updateBalancingState(ConnectivityState.CONNECTING, this);
  }

  RlsSubchannelStateManager getSubchannelStateManager() {
    return subchannelStateManager;
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    if (this.rlsClient == null) {
      return PickResult.withNoResult();
    }

    String[] methodName = args.getMethodDescriptor().getFullMethodName().split("/", 2);
    RouteLookupRequest request =
        requestFactory.create(methodName[0], methodName[1], args.getHeaders());
    final CachedResponse response = rlsClient.get(request);

    if (response.hasValidData()) {
      ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
      ConnectivityState connectivityState = childPolicyWrapper.getConnectivityState();
      switch (connectivityState) {
        case CONNECTING:
          return handlePendingRequest(args);
        case IDLE:
          if (childPolicyWrapper.getPicker() == null) {
            return PickResult.withNoResult();
          }
          return childPolicyWrapper.getPicker().pickSubchannel(args);
        case READY:
          return childPolicyWrapper.getPicker().pickSubchannel(args);
        case TRANSIENT_FAILURE:
          return handleError(Status.INTERNAL);
        case SHUTDOWN:
        default:
          return handleError(Status.ABORTED);
      }
    } else if (response.hasError()) {
      return handleError(response.getStatus());
    } else {
      // pending request
      return handlePendingRequest(args);
    }
  }

  private PickResult handleError(Status cause) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        return PickResult.withError(cause);
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        return useFallback(/* args= */ null, /* blocking= */ true);
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        return useFallback(/* args= */ null, /* blocking= */ false);
      default:
        throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
    }
  }

  private PickResult handlePendingRequest(PickSubchannelArgs args) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        // fall-through
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        return PickResult.withNoResult();
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        // use default target
        return useFallback(args, /* blocking= */ false);
      default:
        throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
    }
  }

  private ChildPolicyWrapper fallbackChildPolicyWrapper;

  /** Uses Subchannel connected to default target. */
  private PickResult useFallback(PickSubchannelArgs args, boolean blocking) {
    CountDownLatch readyLatch = new CountDownLatch(0);
    String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    if (fallbackChildPolicyWrapper == null
        || !fallbackChildPolicyWrapper.getTarget().equals(defaultTarget)) {
      readyLatch = startFallbackChildPolicy();
    }
    if (blocking) {
      try {
        readyLatch
            .await(
                lbPolicyConfiguration.getRouteLookupConfig().getLookupServiceTimeoutInMillis(),
                TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return PickResult.withError(Status.ABORTED.withDescription("interrupted"));
      } catch (Exception e) {
        return PickResult.withError(Status.fromThrowable(e));
      }
    }
    System.out.println("FALLBACK!!!");
    switch (fallbackChildPolicyWrapper.getConnectivityState()) {
      case CONNECTING:
        return PickResult.withNoResult();
      case TRANSIENT_FAILURE:
        // fall-through
      case SHUTDOWN:
        //TODO store status from child to return
        return PickResult.withError(Status.UNKNOWN);
      case IDLE:
        SubchannelPicker picker = fallbackChildPolicyWrapper.getPicker();
        if (picker == null) {
          return PickResult.withNoResult();
        }
        return picker.pickSubchannel(args);
      case READY:
        return fallbackChildPolicyWrapper.getPicker().pickSubchannel(args);
      default:
        throw new AssertionError();
    }
  }

  private CountDownLatch startFallbackChildPolicy() {
    final String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    fallbackChildPolicyWrapper = ChildPolicyWrapper.createOrGet(defaultTarget);
    final CountDownLatch readyLatch = new CountDownLatch(1);

    final LoadBalancerProvider lbProvider =
        lbPolicyConfiguration.getLoadBalancingPolicy().getEffectiveLbProvider();
    ChildLoadBalancerHelper delegate = new ChildLoadBalancerHelper(helper);
    delegate.setRlsPicker(this);
    delegate.setTarget(defaultTarget);
    ChildPolicyReportingHelper childPolicyReportingHelper =
        new ChildPolicyReportingHelper(delegate, fallbackChildPolicyWrapper);
    final LoadBalancer lb =
        lbProvider.newLoadBalancer(childPolicyReportingHelper);
    final ConfigOrError lbConfig =
        lbProvider
            .parseLoadBalancingPolicyConfig(
                lbPolicyConfiguration
                    .getLoadBalancingPolicy()
                    .getEffectiveChildPolicy(defaultTarget));
    fallbackChildPolicyWrapper.setChildPolicy(lbPolicyConfiguration.getLoadBalancingPolicy());
    fallbackChildPolicyWrapper.setLoadBalancer(lb);
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            lb.handleResolvedAddresses(childLbResolvedAddressFactory.create(lbConfig.getConfig()));
            lb.requestConnection();
            readyLatch.countDown();
          }
        });
    return readyLatch;
  }

  @SuppressWarnings("deprecation")
  void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    // need a map of Subchannel to LB
    //TODO remove this hack
    ChildPolicyWrapper.lbs.get(subchannel).handleSubchannelState(subchannel, stateInfo);
  }

  abstract static class RlsSubchannelStateListener {
    abstract void onSubchannelStateChange(String target, ConnectivityState newState);
  }

  static final class RlsSubchannelStateManager {
    ConcurrentHashMap<String, ConnectivityState> stateMap = new ConcurrentHashMap<>();
    Multiset<ConnectivityState> stateMultiset = ConcurrentHashMultiset.create();

    void registerNewState(String name, ConnectivityState newState) {
      ConnectivityState existing;
      if (newState == ConnectivityState.SHUTDOWN) {
        existing = stateMap.remove(name);
      } else {
        existing = stateMap.put(checkNotNull(name, "name"), checkNotNull(newState, "newState"));
        stateMultiset.add(newState);
      }
      if (existing != null) {
        stateMultiset.remove(existing);
      }
    }

    @Nullable
    ConnectivityState getState(String name) {
      return stateMap.get(checkNotNull(name, "name"));
    }

    ConnectivityState getAggregatedState() {
      if (stateMultiset.contains(ConnectivityState.READY)) {
        return ConnectivityState.READY;
      } else if (stateMultiset.contains(ConnectivityState.CONNECTING)) {
        return ConnectivityState.CONNECTING;
      } else if (stateMultiset.contains(ConnectivityState.IDLE)) {
        return ConnectivityState.IDLE;
      } else if (stateMultiset.contains(ConnectivityState.TRANSIENT_FAILURE)) {
        return ConnectivityState.TRANSIENT_FAILURE;
      }
      // empty or shutdown
      return ConnectivityState.IDLE;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("stateMap", stateMap)
          .toString();
    }
  }
}
