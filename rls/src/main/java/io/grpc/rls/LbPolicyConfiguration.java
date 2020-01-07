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

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.internal.ObjectPool;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final List<Map<String, ?>> childPolicies;

    public LoadBalancingPolicy(String childPolicyConfigTargetFieldName,
        List<Map<String, ?>> childPolicies) {
      this.childPolicyConfigTargetFieldName = childPolicyConfigTargetFieldName;
      this.childPolicies = childPolicies;
    }

    public String getChildPolicyConfigTargetFieldName() {
      return childPolicyConfigTargetFieldName;
    }

    public List<Map<String, ?>> getChildPolicies() {
      return childPolicies;
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
