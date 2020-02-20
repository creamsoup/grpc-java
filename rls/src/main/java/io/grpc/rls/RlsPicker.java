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

import static io.opencensus.internal.Utils.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
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

public class RlsPicker extends SubchannelPicker {

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

  // TODO manage connectivity status

  public RlsPicker(
      LbPolicyConfiguration lbPolicyConfiguration,
      AsyncCachingRlsClient rlsClient,
      Helper helper,
      ChildLbResolvedAddressFactory childLbResolvedAddressFactory) {
    this.lbPolicyConfiguration = lbPolicyConfiguration;
    this.rlsClient = rlsClient;
    this.helper = helper;
    this.requestFactory = new RlsRequestFactory(lbPolicyConfiguration.getRouteLookupConfig());
    this.strategy = lbPolicyConfiguration.getRouteLookupConfig().getRequestProcessingStrategy();
    helper.updateBalancingState(ConnectivityState.CONNECTING, this);
    rlsClient.addOobChannelStateListener(new RlsSubchannelStateListener() {

      @Override
      void onSubchannelStateChange(String target, ConnectivityState newState) {
        // this is just informative, not really used
        subchannelStateManager.registerOobState(newState);
      }
    });
    this.childLbResolvedAddressFactory = childLbResolvedAddressFactory;
    System.out.println("rls picker created");
  }

  public RlsSubchannelStateManager getSubchannelStateManager() {
    return subchannelStateManager;
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    //TODO(creamsoup) use subchannel manager
    System.out.println("===================================================");
    if (this.rlsClient == null) {
      System.out.println("client is not set, pending pick!");
      return PickResult.withNoResult();
    }

    String[] methodName = args.getMethodDescriptor().getFullMethodName().split("/", 2);
    RouteLookupRequest request =
        requestFactory.create(methodName[0], methodName[1], args.getHeaders());
    System.out.println("pick request: " + request);
    final CachedResponse response = rlsClient.get(request);
    System.out.println("response: " + response);

    if (response.hasValidData()) {
      ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
      System.out.println(">>>> valid data! subchannel state: "
          + childPolicyWrapper.getConnectivityState());
      switch (childPolicyWrapper.getConnectivityState()) {
        case IDLE:
        case CONNECTING:
          // System.out.println("??? pending from valid");
          // return handlePendingRequest(args);
        case READY:
          System.out.println("??? good");
          return childPolicyWrapper.getPicker().pickSubchannel(args);
        case TRANSIENT_FAILURE:
          System.out.println("??? transient failure");
          return handleError(Status.INTERNAL);
        case SHUTDOWN:
        default:
          System.out.println("??? aborted");
          return handleError(Status.ABORTED);
      }
    } else if (response.hasError()) {
      System.out.println(">>>> error");
      return handleError(response.getStatus());
    } else {
      System.out.println(">>>> pending");
      // pending request
      return handlePendingRequest(args);
    }
  }

  private PickResult handleError(Status cause) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        System.out.println("with error");
        return PickResult.withError(cause);
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        return useFallback(/* args= */ null, /* blocking= */ true);
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        return useFallback(/* args= */ null, /* blocking= */ false);
    }
    throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
  }

  private PickResult handlePendingRequest(PickSubchannelArgs args) {
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        // fall-through
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        System.out.println("??? final pending");
        return PickResult.withNoResult();
      case ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS:
        // use default target
        return useFallback(args, /* blocking= */ false);
    }
    throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
  }

  private ChildPolicyWrapper fallbackChildPolicyWrapper;

  /** Uses Subchannel connected to default target. */
  private PickResult useFallback(PickSubchannelArgs args, boolean blocking) {
    System.out.println("use fallback! blocking: " + blocking + " args: " + args);
    CountDownLatch readyLatch = new CountDownLatch(0);
    String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    if (fallbackChildPolicyWrapper == null
        || !fallbackChildPolicyWrapper.getTarget().equals(defaultTarget)) {
      readyLatch = startFallbackChildPolicy();
    }
    if (blocking) {
      System.out.println("waiting to be connected");
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

    switch (fallbackChildPolicyWrapper.getConnectivityState()) {
      case IDLE:
        // fall-through
      case CONNECTING:
        System.out.println("fallback no result");
        return PickResult.withNoResult();
      case TRANSIENT_FAILURE:
        // fall-through
      case SHUTDOWN:
        System.out.println("fallback shutdown error");
        return PickResult.withError(Status.UNKNOWN);
      case READY:
        System.out.println("fallback ready!");
        //TODO removeme
        return fallbackChildPolicyWrapper.getPicker().pickSubchannel(args);
    }
    throw new AssertionError();
  }

  private CountDownLatch startFallbackChildPolicy() {
    final String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    fallbackChildPolicyWrapper = ChildPolicyWrapper.createOrGet(defaultTarget);
    System.out.println("connecting to fallback channel, target: " + defaultTarget);
    final CountDownLatch readyLatch = new CountDownLatch(1);

    LoadBalancerProvider lbProvider =
        lbPolicyConfiguration.getLoadBalancingPolicy().getEffectiveLbProvider();
    ChildPolicyReportingHelper childPolicyReportingHelper =
        new ChildPolicyReportingHelper(
            new ChildLoadBalancerHelper(helper), fallbackChildPolicyWrapper);
    final LoadBalancer lb =
        lbProvider.newLoadBalancer(childPolicyReportingHelper);
    childPolicyReportingHelper.setLb(lb);
    final ConfigOrError lbConfig = lbProvider
        .parseLoadBalancingPolicyConfig(
            lbPolicyConfiguration.getLoadBalancingPolicy().getEffectiveChildPolicy(defaultTarget));
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            lb.handleResolvedAddresses(childLbResolvedAddressFactory.create(lbConfig.getConfig()));
            lb.requestConnection();
          }
        });
    return readyLatch;
  }

  static abstract class RlsSubchannelStateListener {
    abstract void onSubchannelStateChange(String target, ConnectivityState newState);
  }

  static final class RlsSubchannelStateManager {
    ConnectivityState oobChannelState = ConnectivityState.IDLE;
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
      System.out.println("new State registered: " + name + " " + newState
          + " / aggState: " + getAggregatedState());
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

    public void registerOobState(ConnectivityState newState) {
      oobChannelState = newState;
    }
  }
}
