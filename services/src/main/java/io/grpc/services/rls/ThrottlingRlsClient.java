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

package io.grpc.services.rls;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ManagedChannel;
import io.grpc.alts.GoogleDefaultChannelBuilder;
import io.grpc.internal.GrpcUtil;
import io.grpc.lookup.v1alpha1.RouteLookupRequest;
import io.grpc.lookup.v1alpha1.RouteLookupResponse;
import io.grpc.lookup.v1alpha1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1alpha1.RouteLookupServiceGrpc.RouteLookupServiceFutureStub;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A RlsClient using {@link AdaptiveThrottler}.
 */
public final class ThrottlingRlsClient {

  private static final Logger logger = Logger.getLogger(ThrottlingRlsClient.class.getName());

  private final ManagedChannel channel;
  private final RouteLookupServiceFutureStub stub;
  private final Throttler throttler;

  private ThrottlingRlsClient(String target, Throttler throttler) {
    //TODO: use channel credentials from parent channel.
    checkNotNull(target, "target");
    channel = GoogleDefaultChannelBuilder.forTarget(target).build();
    stub = RouteLookupServiceGrpc.newFutureStub(channel);
    this.throttler = checkNotNull(throttler, "throttler");
  }

  public static ThrottlingRlsClient forAddress(String name, int port, Throttler throttler) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port), throttler);
  }

  //TODO: consider using builder
  public static ThrottlingRlsClient forTarget(String target, Throttler throttler) {
    return new ThrottlingRlsClient(target, throttler);
  }

  /**
   * Calls lookup method from route lookup service asynchronously.
   */
  public ListenableFuture<RouteLookupResponse> lookup(RouteLookupRequest request) {
    if (throttler.shouldThrottle()) {
      if (logger.isLoggable(Level.FINE)) {
        logger.fine("RouteLookupRequest is throttled: " + request);
      }
      return Futures.immediateFuture(null);
    }
    final ListenableFuture<RouteLookupResponse> resultFuture = stub.routeLookup(request);
    // add listener to report back the result of RPC to throttler
    resultFuture.addListener(
        new Runnable() {
          @Override
          public void run() {
            if (resultFuture.isDone() && !resultFuture.isCancelled()) {
              try {
                resultFuture.get();
                throttler.registerBackendResponse(true);
                return;
              } catch (Exception e) {
                // fall-through
              }
              throttler.registerBackendResponse(false);
            }
          }
        },
        MoreExecutors.directExecutor());
    return resultFuture;
  }
}
