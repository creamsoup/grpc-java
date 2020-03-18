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
import static com.google.common.base.Preconditions.checkState;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.util.ForwardingLoadBalancerHelper;
import javax.annotation.Nonnull;

final class ChildLoadBalancerHelper extends ForwardingLoadBalancerHelper {

  private final Helper rlsHelper;
  private final String target;
  private final RlsPicker rlsPicker;

  private ChildLoadBalancerHelper(String target, Helper rlsHelper, RlsPicker rlsPicker) {
    this.target = checkNotNull(target, "target");
    this.rlsHelper = checkNotNull(rlsHelper, "rlsHelper");
    this.rlsPicker = checkNotNull(rlsPicker, "rlsPicker");
  }

  @Override
  protected Helper delegate() {
    return rlsHelper;
  }

  @Override
  public void updateBalancingState(
      @Nonnull ConnectivityState newState,
      @Nonnull SubchannelPicker unused) {
    checkState(rlsPicker != null, "Must provide RlsPicker before update balancing state");
    ConnectivityState newAggState = updateLbState(target, newState);
    rlsHelper.updateBalancingState(newAggState, rlsPicker);
  }

  private ConnectivityState updateLbState(String target, ConnectivityState state) {
    rlsPicker.getSubchannelStateManager().registerNewState(target, state);
    return rlsPicker.getSubchannelStateManager().getAggregatedState();
  }

  static final class ChildLoadBalancerHelperProvider {
    private final Helper helper;
    private final RlsPicker rlsPicker;

    public ChildLoadBalancerHelperProvider(Helper helper, RlsPicker rlsPicker) {
      this.helper = helper;
      this.rlsPicker = rlsPicker;
    }

    public ChildLoadBalancerHelper forTarget(String target) {
      return new ChildLoadBalancerHelper(target, helper, rlsPicker);
    }
  }
}
