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
  private SubchannelStateManager subchannelStateManager = mock(SubchannelStateManager.class);
  private SubchannelPicker picker = mock(SubchannelPicker.class);

  private ChildLoadBalancerHelperProvider provider =
      new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker);

  @Test
  public void childLoadBalancerHelper_shouldReportsSubchannelState() {
    ChildLoadBalancerHelper childLbHelper = provider.forTarget("foo.bar.com");
    when(subchannelStateManager.getAggregatedState()).thenReturn(ConnectivityState.READY);

    childLbHelper.updateBalancingState(ConnectivityState.READY, mock(SubchannelPicker.class));

    // should use picker from the provider
    verify(helper).updateBalancingState(ConnectivityState.READY, picker);
    verify(subchannelStateManager).registerNewState("foo.bar.com", ConnectivityState.READY);
  }
}