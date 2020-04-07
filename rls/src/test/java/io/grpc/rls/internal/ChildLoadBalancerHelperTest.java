/*
 * Copyright 2020 The gRPC Authors
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

import static org.mockito.Mockito.mock;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ChildLoadBalancerHelperTest {

  private final Helper helper = mock(Helper.class);
  private final SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();
  private final SubchannelPicker picker = mock(SubchannelPicker.class);
  private final ChildLoadBalancerHelperProvider provider =
      new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker);

  @Test
  public void childLoadBalancerHelper_shouldReportsSubchannelState() {
    InOrder inOrder = Mockito.inOrder(helper);
    ChildLoadBalancerHelper childLbHelper1 = provider.forTarget("foo.com");
    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    ChildLoadBalancerHelper childLbHelper2 = provider.forTarget("bar.com");
    SubchannelPicker picker2 = mock(SubchannelPicker.class);

    childLbHelper1.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, picker1);
    inOrder.verify(helper).updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, picker);

    childLbHelper2.updateBalancingState(ConnectivityState.CONNECTING, picker2);
    inOrder.verify(helper).updateBalancingState(ConnectivityState.CONNECTING, picker);

    childLbHelper1.updateBalancingState(ConnectivityState.READY, picker1);
    inOrder.verify(helper).updateBalancingState(ConnectivityState.READY, picker);

    childLbHelper1.updateBalancingState(ConnectivityState.SHUTDOWN, picker1);
    inOrder.verify(helper).updateBalancingState(ConnectivityState.CONNECTING, picker);
  }
}