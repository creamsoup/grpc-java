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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Objects;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.ObjectPool;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A LbPolicyConfiguration is configuration for RLS to delegate request to other LB implementations.
 */
final class LbPolicyConfiguration {

  private final RouteLookupConfig routeLookupConfig;
  private final LoadBalancingPolicy policy;
  // TODO is this the best place?
  private final Map<String /* target */, ObjectPool<ChildPolicyWrapper>> childPolicyMap =
      new HashMap<>();

  public LbPolicyConfiguration(RouteLookupConfig routeLookupConfig, LoadBalancingPolicy policy) {
    this.routeLookupConfig = checkNotNull(routeLookupConfig, "routeLookupConfig");
    this.policy = checkNotNull(policy, "policy");
  }

  public RouteLookupConfig getRouteLookupConfig() {
    return routeLookupConfig;
  }

  public void addChildPolicyWrapper(ChildPolicyWrapper childPolicy) {
    ObjectPool<ChildPolicyWrapper> existing = childPolicyMap.get(childPolicy.target);
    if (existing != null) {
      // TODO(creamsoup) should check if the wrapper is same or not?
      existing.getObject();
    } else {
      childPolicyMap.put(childPolicy.target, new RefCountedChildPolicy(childPolicy));
    }
  }

  public void returnChildPolicyWrapper(ChildPolicyWrapper childPolicy) {
    ObjectPool<ChildPolicyWrapper> existing = childPolicyMap.get(childPolicy.target);
    checkState(existing != null, "doesn't exists!");
    existing.returnObject(childPolicy);
  }

  public LoadBalancingPolicy getLoadBalancingPolicy() {
    return policy;
  }

  public void cleanup() {
    // TODO: verify state or something?
    childPolicyMap.clear();
  }

  static final class ChildPolicyWrapper {
    private final String target;
    private LoadBalancingPolicy policy;
    private ConnectivityState connectivityState;
    private SubchannelPicker picker;
    private Helper helper;

    private final AtomicLong refCnt = new AtomicLong(1);

    public void acquire() {
      long currCnt = refCnt.getAndIncrement();
      if (currCnt <= 0) {
        refCnt.decrementAndGet();
        throw new IllegalStateException("Cannot acquired already");
      }
    }

    public void release() {
      long currCnt = refCnt.decrementAndGet();
      if (currCnt == 0) {
        policy = null;
        connectivityState = null;
        picker = null;
        helper = null;
      } else if (currCnt < 0) {
        throw new IllegalStateException("Cannot release already released object");
      }
    }

    public ChildPolicyWrapper(String target) {
      this.target = target;
    }

    public String getTarget() {
      return target;
    }

    public LoadBalancingPolicy getPolicy() {
      return policy;
    }

    public void setPolicy(LoadBalancingPolicy policy) {
      this.policy = policy;
    }

    public ConnectivityState getConnectivityState() {
      return connectivityState;
    }

    public void setConnectivityState(ConnectivityState connectivityState) {
      this.connectivityState = connectivityState;
    }

    public SubchannelPicker getPicker() {
      return picker;
    }

    public void setPicker(SubchannelPicker picker) {
      this.picker = picker;
    }

    public Helper getHelper() {
      return helper;
    }

    public void setHelper(Helper helper) {
      this.helper = helper;
    }
  }

  static final class LoadBalancingPolicy {
    private final String childPolicyConfigTargetFieldName;
    private final Map<String, ?> effectiveChildPolicy;
    private final LoadBalancerProvider effectiveLbProvider;

    public LoadBalancingPolicy(
        String childPolicyConfigTargetFieldName, List<Map<String, ?>> childPolicies) {
      this.childPolicyConfigTargetFieldName = childPolicyConfigTargetFieldName;
      Map<String, ?> effectiveChildPolicy = null;
      LoadBalancerProvider effectiveLbProvider = null;
      List<String> policyTried = new ArrayList<>();

      LoadBalancerRegistry lbRegistry = LoadBalancerRegistry.getDefaultRegistry();
      for (Map<String, ?> childPolicy : childPolicies) {
        if (childPolicy.isEmpty()) {
          continue;
        }
        String policyName = childPolicy.keySet().iterator().next();
        LoadBalancerProvider provider = lbRegistry.getProvider(policyName);
        if (provider != null) {
          effectiveLbProvider = provider;
          effectiveChildPolicy = Collections.unmodifiableMap(childPolicy);
          break;
        }
        policyTried.add(policyName);
      }
      checkState(effectiveChildPolicy != null && effectiveLbProvider != null,
          "no valid childPolicy found, policy tried: %s", policyTried);
      this.effectiveChildPolicy = effectiveChildPolicy;
      this.effectiveLbProvider = effectiveLbProvider;
    }

    public String getChildPolicyConfigTargetFieldName() {
      return childPolicyConfigTargetFieldName;
    }

    public Map<String, ?> getEffectiveChildPolicy(String target) {
      return effectiveChildPolicy;
    }

    public LoadBalancerProvider getEffectiveLbProvider() {
      return effectiveLbProvider;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LoadBalancingPolicy that = (LoadBalancingPolicy) o;
      return Objects.equal(childPolicyConfigTargetFieldName, that.childPolicyConfigTargetFieldName)
          && Objects.equal(effectiveChildPolicy, that.effectiveChildPolicy)
          && Objects.equal(effectiveLbProvider, that.effectiveLbProvider);
    }

    @Override
    public int hashCode() {
      return Objects
          .hashCode(childPolicyConfigTargetFieldName, effectiveChildPolicy, effectiveLbProvider);
    }
  }

  private static final class RefCountedChildPolicy implements ObjectPool<ChildPolicyWrapper> {

    private final ChildPolicyWrapper childPolicy;
    private AtomicInteger refCount = new AtomicInteger(1);

    public RefCountedChildPolicy(ChildPolicyWrapper childPolicy) {
      this.childPolicy = checkNotNull(childPolicy, "childPolicy");
    }

    @Override
    public ChildPolicyWrapper getObject() {
      checkState(refCount.getAndIncrement() != 0, "bad!");
      return childPolicy;
    }

    @Override
    public ChildPolicyWrapper returnObject(Object object) {
      checkArgument(childPolicy == object, "got invalid object");
      int newCount = refCount.decrementAndGet();
      checkState(newCount >= 0, "already returned?");
      if (newCount == 0) {
        cleanup();
        return null;
      }
      return childPolicy;
    }

    private void cleanup() {
      //TODO impl
    }
  }
}
