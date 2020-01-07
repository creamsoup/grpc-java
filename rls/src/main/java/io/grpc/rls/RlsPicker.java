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

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.RouteLookupClient.RouteLookupInfo;
import io.grpc.rls.Throttler.ThrottledException;
import java.util.concurrent.ExecutionException;

public class RlsPicker extends SubchannelPicker {

  private LbPolicyConfiguration lbPolicyConfiguration;
  private RouteLookupClient rlsClient;
  private Helper helper;
  private RlsRequestFactory requestFactory;
  private RequestProcessingStrategy strategy;
  private State state = State.OKAY;
  private Status error = null;

  public RlsPicker(
      LbPolicyConfiguration lbPolicyConfiguration, RouteLookupClient client, Helper helper) {
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
      return processCacheHit(response, args);
    }
    return handlePendingRequest(request, response, args);
  }

  private PickResult processCacheHit(RouteLookupInfo lookupInfo, PickSubchannelArgs args) {
    try {
      RouteLookupResponse unused = lookupInfo.get();
      // TODO are those errors are possible? if not remove handle error, also make sure this is true
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return handleError(e);
    } catch (ExecutionException e) {
      return handleError(e.getCause());
    }
    // cache hit
    ChildPolicyWrapper childPolicyWrapper = lookupInfo.getChildPolicyWrapper();
    // TODO delegate to the picker from the associated child policy.
    return childPolicyWrapper.getPicker().pickSubchannel(args);
  }

  private PickResult handlePendingRequest(
      RouteLookupRequest request, final RouteLookupInfo response, PickSubchannelArgs args) {
    // TODO populate subchannel picker etc.
    response.addListener(new Runnable() {
      @Override
      public void run() {
        if (response.isDone() && !response.isCancelled()) {
          // This need to be picker of child policy using targetName the target
          ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
          childPolicyWrapper.setPicker();
          childPolicyWrapper.setPolicy();
          childPolicyWrapper.setConnectivityState();
          childPolicyWrapper.setHelper(helper);
        }
      }
    }, MoreExecutors.directExecutor());
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

  /** Uses Subchannel connected to default target. */
  private PickResult useFallbackSubchannel() {
    if (fallbackSubchannel == null) {
      String target = lbPolicyConfiguration.getRouteLookupConfig().getDefaultTarget();
      // TODO: create subchannel using target (also inherit credentials?)
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
