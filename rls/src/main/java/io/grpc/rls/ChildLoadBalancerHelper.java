package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.util.ForwardingLoadBalancerHelper;
import javax.annotation.Nonnull;

public final class ChildLoadBalancerHelper extends ForwardingLoadBalancerHelper {

  private final Helper rlsHelper;
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

  @Override
  public void updateBalancingState(
      @Nonnull ConnectivityState newState,
      @Nonnull SubchannelPicker unused) {
    System.out.println("CLBH: updating balancing status " + newState);
    checkState(rlsPicker != null, "Must provide RlsPicker before update balancing state");
    super.updateBalancingState(newState, rlsPicker);
  }

  public void updateLbState(String target, ConnectivityState state) {
    System.out.println("CLBH: updating lb state: " +target + " / " + state);
    rlsPicker.getSubchannelStateManager().registerNewState(target, state);
  }
}
