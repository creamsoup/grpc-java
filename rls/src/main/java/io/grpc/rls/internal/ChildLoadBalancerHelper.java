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
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.util.ForwardingLoadBalancerHelper;
import javax.annotation.Nonnull;

/**
 * A delegating {@link Helper} notifies {@link RlsPicker} when lb status is changed.
 */
final class ChildLoadBalancerHelper extends ForwardingLoadBalancerHelper {

  private final Helper rlsHelper;
  private final String target;
  private final SubchannelStateManager subchannelStateManager;
  private final SubchannelPicker picker;

  private ChildLoadBalancerHelper(
      String target,
      Helper rlsHelper,
      SubchannelStateManager subchannelStateManager,
      SubchannelPicker picker) {
    this.target = checkNotNull(target, "target");
    this.rlsHelper = checkNotNull(rlsHelper, "rlsHelper");
    this.subchannelStateManager = checkNotNull(subchannelStateManager, "subchannelStateManager");
    this.picker = checkNotNull(picker, "picker");
  }

  @Override
  protected Helper delegate() {
    return rlsHelper;
  }

  @Override
  public void updateBalancingState(
      @Nonnull ConnectivityState newState,
      @Nonnull SubchannelPicker unused) {
    ConnectivityState newAggState = updateLbState(target, newState);
    super.updateBalancingState(newAggState, picker);
  }

  private ConnectivityState updateLbState(String target, ConnectivityState state) {
    subchannelStateManager.updateState(target, state);
    return subchannelStateManager.getAggregatedState();
  }

  static final class ChildLoadBalancerHelperProvider {
    private final Helper helper;
    private final SubchannelStateManager subchannelStateManager;
    private final SubchannelPicker picker;

    public ChildLoadBalancerHelperProvider(
        Helper helper, SubchannelStateManager subchannelStateManager, SubchannelPicker picker) {
      this.helper = checkNotNull(helper, "helper");
      this.subchannelStateManager = checkNotNull(subchannelStateManager, "subchannelStateManager");
      this.picker = checkNotNull(picker, "picker");
    }

    public ChildLoadBalancerHelper forTarget(String target) {
      return new ChildLoadBalancerHelper(target, helper, subchannelStateManager, picker);
    }
  }
}
