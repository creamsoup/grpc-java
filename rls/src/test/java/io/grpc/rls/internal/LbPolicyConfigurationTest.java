package io.grpc.rls.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableMap;
import io.grpc.LoadBalancerProvider;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LbPolicyConfigurationTest {

  @Test
  public void childPolicyWrapper_refCounted() {
    String target = "target";
    ChildPolicyWrapper childPolicy = ChildPolicyWrapper.createOrGet(target);
    assertThat(ChildPolicyWrapper.childPolicyMap.keySet()).containsExactly(target);

    ChildPolicyWrapper childPolicy2 = ChildPolicyWrapper.createOrGet(target);
    assertThat(ChildPolicyWrapper.childPolicyMap.keySet()).containsExactly(target);

    childPolicy2.release();
    assertThat(ChildPolicyWrapper.childPolicyMap.keySet()).containsExactly(target);

    childPolicy.release();
    assertThat(ChildPolicyWrapper.childPolicyMap).isEmpty();

    try {
      childPolicy.release();
      fail("should not be able to access already released policy");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("already released");
    }
  }

  @Test
  public void childLoadBalancingPolicy_effectiveChildPolicy() {
    LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
    ChildLoadBalancingPolicy childLbPolicy =
        new ChildLoadBalancingPolicy(
            "targetFieldName",
            ImmutableMap.<String, Object>of("foo", "bar"),
            mockProvider);

    assertThat(childLbPolicy.getEffectiveChildPolicy("target"))
        .containsExactly("foo", "bar", "targetFieldName", "target");
    assertThat(childLbPolicy.getEffectiveLbProvider()).isEqualTo(mockProvider);
  }
}