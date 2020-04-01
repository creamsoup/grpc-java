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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ChildLoadBalancerHelperTest {

  private Helper helper = mock(Helper.class);
  private SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();
  private SubchannelPicker picker = mock(SubchannelPicker.class);

  private ChildLoadBalancerHelperProvider provider =
      new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker);

  @Test
  public void childLoadBalancerHelper_shouldReportsSubchannelState() {
    ChildLoadBalancerHelper childLbHelper1 = provider.forTarget("foo.com");
    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    childLbHelper1.updateBalancingState(ConnectivityState.READY, picker1);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker);

    ChildLoadBalancerHelper childLbHelper2 = provider.forTarget("bar.com");
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    childLbHelper2.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, picker2);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker);

    childLbHelper1.updateBalancingState(ConnectivityState.SHUTDOWN, picker1);
    verify(helper).updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, picker);

    childLbHelper2.updateBalancingState(ConnectivityState.CONNECTING, picker1);
    verify(helper).updateBalancingState(ConnectivityState.CONNECTING, picker);
  }
}