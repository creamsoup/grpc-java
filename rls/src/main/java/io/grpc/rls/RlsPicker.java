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

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.rls.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.Throttler.ThrottledException;
import java.util.concurrent.ExecutionException;

public class RlsPicker extends SubchannelPicker {

  private LbPolicyConfiguration lbPolicyConfiguration;
  private RlsRequestFactory requestFactory;
  private RouteLookupClient rlsClient;
  private RequestProcessingStrategy strategy;
  // TODO: use listenable future to do stuff for above things to LbPolicyConfiguration

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    String target = args.getMethodDescriptor().getServiceName();
    String path = args.getMethodDescriptor().getFullMethodName();

    RouteLookupRequest request = requestFactory.create(target, path, args.getHeaders());
    ListenableFuture<RouteLookupResponse> response = rlsClient.routeLookup(request);

    if (response.isDone()) {
      try {
        return processCacheHit(response.get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // handle wrapping up here
      } catch (ExecutionException e) {
        return handleError(e.getCause());
      }
    }
    return handlePendingRequest();
  }

  private PickResult processCacheHit(RouteLookupResponse response) {
    // cache hit
    // delegate to picker from associated child policy? from?

    return PickResult.withSubchannel(null);
  }

  private PickResult handlePendingRequest() {
    // reaching here means pending
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
}
