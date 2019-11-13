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

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import java.util.Map;

public class RlsLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    // higher than grpclb=5
    return 7;
  }

  @Override
  public String getPolicyName() {
    return "rls";
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    //TODO also needs key builder map, request cache, lb policy config
    return new RlsLoadBalancer(helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingConfigPolicy) {
    try {
      return ConfigOrError.fromConfig(LbPolicyConfiguration.from(rawLoadBalancingConfigPolicy));
    } catch (Exception e) {
      return ConfigOrError.fromError(
          Status.INTERNAL.withDescription("can't parse config: " + e.getMessage()).withCause(e));
    }
  }
}
