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

package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkNotNull;

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
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.rls.internal.AsyncCachingRlsClient.CachedResponse;
import io.grpc.rls.internal.AsyncCachingRlsClient.ChildPolicyReportingHelper;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.internal.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.internal.RlsProtoData.RouteLookupRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class RlsPicker extends SubchannelPicker {

  /** A header will be added when RLS server respond with additional header data. */
  public static final Metadata.Key<String> RLS_DATA_KEY =
      Metadata.Key.of("X-Google-RLS-Data", Metadata.ASCII_STRING_MARSHALLER);

  private final SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();
  private final ChildLbResolvedAddressFactory childLbResolvedAddressFactory;
  private final ChildLoadBalancerHelperProvider childLbHelperProvider;

  private LbPolicyConfiguration lbPolicyConfiguration;
  @Nullable
  private AsyncCachingRlsClient rlsClient;
  private RlsRequestFactory requestFactory;
  private Helper helper;

  RlsPicker(
      LbPolicyConfiguration lbPolicyConfiguration,
      AsyncCachingRlsClient rlsClient,
      Helper helper,
      ChildLbResolvedAddressFactory childLbResolvedAddressFactory) {
    this.lbPolicyConfiguration = checkNotNull(lbPolicyConfiguration, "lbPolicyConfiguration");
    this.rlsClient = checkNotNull(rlsClient, "rlsClient");
    this.helper = checkNotNull(helper, "helper");
    this.requestFactory = new RlsRequestFactory(lbPolicyConfiguration.getRouteLookupConfig());
    this.childLbResolvedAddressFactory =
        checkNotNull(childLbResolvedAddressFactory, "childLbResolvedAddressFactory");
    helper.updateBalancingState(ConnectivityState.CONNECTING, this);
    this.childLbHelperProvider =
        new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, this);
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

    PickSubchannelArgs rlsAppliedArgs = getApplyRlsHeader(args, response);
    if (response.hasValidData()) {
      ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
      ConnectivityState connectivityState =
          childPolicyWrapper.getConnectivityStateInfo().getState();
      switch (connectivityState) {
        case CONNECTING:
          return PickResult.withNoResult();
        case IDLE:
          if (childPolicyWrapper.getPicker() == null) {
            return PickResult.withNoResult();
          }
          // fall through
        case READY:
          return childPolicyWrapper.getPicker().pickSubchannel(rlsAppliedArgs);
        case TRANSIENT_FAILURE:
          return handleError(rlsAppliedArgs, Status.INTERNAL);
        case SHUTDOWN:
        default:
          return handleError(rlsAppliedArgs, Status.ABORTED);
      }
    } else if (response.hasError()) {
      return handleError(rlsAppliedArgs, response.getStatus());
    } else {
      return PickResult.withNoResult();
    }
  }

  private PickSubchannelArgs getApplyRlsHeader(PickSubchannelArgs args, CachedResponse response) {
    if (response.getHeaderData() == null || response.getHeaderData().isEmpty()) {
      return args;
    }

    Metadata headers = new Metadata();
    headers.merge(args.getHeaders());
    headers.put(RLS_DATA_KEY, response.getHeaderData());
    return new PickSubchannelArgsImpl(args.getMethodDescriptor(), headers, args.getCallOptions());
  }

  private PickResult handleError(PickSubchannelArgs args, Status cause) {
    RequestProcessingStrategy strategy =
        lbPolicyConfiguration.getRouteLookupConfig().getRequestProcessingStrategy();
    switch (strategy) {
      case SYNC_LOOKUP_CLIENT_SEES_ERROR:
        return PickResult.withError(cause);
      case SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR:
        return useFallback(args);
      default:
        throw new AssertionError("Unknown RequestProcessingStrategy: " + strategy);
    }
  }

  private ChildPolicyWrapper fallbackChildPolicyWrapper;

  /** Uses Subchannel connected to default target. */
  private PickResult useFallback(PickSubchannelArgs args) {
    String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    if (fallbackChildPolicyWrapper == null
        || !fallbackChildPolicyWrapper.getTarget().equals(defaultTarget)) {
      try {
        startFallbackChildPolicy()
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
    SubchannelPicker picker = fallbackChildPolicyWrapper.getPicker();
    switch (fallbackChildPolicyWrapper.getConnectivityStateInfo().getState()) {
      case CONNECTING:
        return PickResult.withNoResult();
      case TRANSIENT_FAILURE:
        // fall through
      case SHUTDOWN:
        return
            PickResult.withError(fallbackChildPolicyWrapper.getConnectivityStateInfo().getStatus());
      case IDLE:
        if (picker == null) {
          return PickResult.withNoResult();
        }
        // fall through
      case READY:
        return picker.pickSubchannel(args);
      default:
        throw new AssertionError();
    }
  }

  private CountDownLatch startFallbackChildPolicy() {
    String defaultTarget = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
    fallbackChildPolicyWrapper = ChildPolicyWrapper.createOrGet(defaultTarget);
    final CountDownLatch readyLatch = new CountDownLatch(1);

    LoadBalancerProvider lbProvider =
        lbPolicyConfiguration.getLoadBalancingPolicy().getEffectiveLbProvider();
    ChildPolicyReportingHelper childPolicyReportingHelper =
        new ChildPolicyReportingHelper(childLbHelperProvider, fallbackChildPolicyWrapper, null);
    final LoadBalancer lb =
        lbProvider.newLoadBalancer(childPolicyReportingHelper);
    final ConfigOrError lbConfig =
        lbProvider
            .parseLoadBalancingPolicyConfig(
                lbPolicyConfiguration
                    .getLoadBalancingPolicy()
                    .getEffectiveChildPolicy(defaultTarget));
    fallbackChildPolicyWrapper.setChildPolicy(lbPolicyConfiguration.getLoadBalancingPolicy());
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

  SubchannelStateManager getSubchannelStateManager() {
    return subchannelStateManager;
  }
}
