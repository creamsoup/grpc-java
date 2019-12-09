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

import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.Status;

@ExperimentalApi("TOOD")
class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  private final RouteLookupClient client;

  RlsLoadBalancer(Helper helper, RouteLookupClient client) {
    this.helper = checkNotNull(helper, "helper");
    this.client = checkNotNull(client, "client");
  }

  @Override
  public void handleNameResolutionError(Status error) {

  }

  @Override
  public void shutdown() {

  }
}
