package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.util.ForwardingLoadBalancerHelper;
import javax.annotation.Nonnull;

final class ChildLoadBalancerHelper extends ForwardingLoadBalancerHelper {

  private final Helper rlsHelper;
  private String target;
  private RlsPicker rlsPicker;

  public ChildLoadBalancerHelper(Helper rlsHelper) {
    this.rlsHelper = checkNotNull(rlsHelper, "rlsHelper");
  }

  @Override
  protected Helper delegate() {
    return rlsHelper;
  }

  public void setRlsPicker(RlsPicker rlsPicker) {
    this.rlsPicker = checkNotNull(rlsPicker, "rlsPicker");
  }

  public void setTarget(String target) {
    this.target = checkNotNull(target, "target");
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
}
