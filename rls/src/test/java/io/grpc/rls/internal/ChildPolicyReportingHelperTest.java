package io.grpc.rls.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.internal.ChildPolicyReportingHelper.ChildLbStatusListener;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import java.net.SocketAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class ChildPolicyReportingHelperTest {

  private final Helper helper = mock(Helper.class);
  private final SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();
  private final SubchannelPicker picker = mock(SubchannelPicker.class);
  private final ChildLoadBalancerHelperProvider helperProvider =
      new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker);
  private final ChildPolicyWrapper childPolicyWrapper =
      ChildPolicyWrapper.createOrGet("foo.google.com");
  private final ChildLbStatusListener childLbStatusListener = mock(ChildLbStatusListener.class);
  private final ChildPolicyReportingHelper childPolicyReportingHelper =
      new ChildPolicyReportingHelper(helperProvider, childPolicyWrapper, childLbStatusListener);

  @Test
  public void name() {
    Subchannel mockSubchannel = mock(Subchannel.class);
    when(helper.createSubchannel(any(CreateSubchannelArgs.class))).thenReturn(mockSubchannel);
    Subchannel subchannel =
        childPolicyReportingHelper.createSubchannel(
            CreateSubchannelArgs.newBuilder()
                .setAddresses(new EquivalentAddressGroup(mock(SocketAddress.class)))
                .build());
    subchannel.start(new SubchannelStateListener() {
      @Override
      public void onSubchannelState(ConnectivityStateInfo newState) {

      }
    });
    assertThat(childPolicyWrapper.getConnectivityStateInfo())
        .isEqualTo(ConnectivityStateInfo.forNonError(ConnectivityState.IDLE));
    verify(childLbStatusListener).onStatusChanged(ConnectivityState.IDLE);
    subchannel.
    // create subchannel
    // check wrapper has connectivity

    // when status changed
    // listener is called, picker is set or not
  }
}