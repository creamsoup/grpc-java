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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ObjectPool;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.internal.RlsProtoData.RouteLookupRequest;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Configuration for RLS load balancing policy.
 */
public final class LbPolicyConfiguration {

  private final RouteLookupConfig routeLookupConfig;
  private final ChildLoadBalancingPolicy policy;

  public LbPolicyConfiguration(
      RouteLookupConfig routeLookupConfig, ChildLoadBalancingPolicy policy) {
    this.routeLookupConfig = checkNotNull(routeLookupConfig, "routeLookupConfig");
    this.policy = checkNotNull(policy, "policy");
  }

  public RouteLookupConfig getRouteLookupConfig() {
    return routeLookupConfig;
  }

  public ChildLoadBalancingPolicy getLoadBalancingPolicy() {
    return policy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LbPolicyConfiguration that = (LbPolicyConfiguration) o;
    return Objects.equals(routeLookupConfig, that.routeLookupConfig)
        && Objects.equals(policy, that.policy);
  }

  @Override
  public int hashCode() {
    return Objects.hash(routeLookupConfig, policy);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("routeLookupConfig", routeLookupConfig)
        .add("policy", policy)
        .toString();
  }

  /** ChildLoadBalancingPolicy is an elected child policy to delegate requests. */
  public static final class ChildLoadBalancingPolicy {

    private final Map<String, Object> effectiveRawChildPolicy;
    private final LoadBalancerProvider effectiveLbProvider;
    private final String childPolicyConfigTargetFieldName;
    private final Map<RouteLookupRequest, BackoffPolicy> pendingRequests = new HashMap<>();

    @VisibleForTesting
    ChildLoadBalancingPolicy(
        String childPolicyConfigTargetFieldName,
        Map<String, Object> effectiveRawChildPolicy,
        LoadBalancerProvider effectiveLbProvider) {
      checkArgument(
          childPolicyConfigTargetFieldName != null && !childPolicyConfigTargetFieldName.isEmpty(),
          "childPolicyConfigTargetFieldName cannot be empty or null");
      this.childPolicyConfigTargetFieldName = childPolicyConfigTargetFieldName;
      this.effectiveRawChildPolicy =
          checkNotNull(effectiveRawChildPolicy, "effectiveRawChildPolicy");
      this.effectiveLbProvider = checkNotNull(effectiveLbProvider, "effectiveRawChildPolicy");
    }

    /** Creates ChildLoadBalancingPolicy. */
    @SuppressWarnings("unchecked")
    public static ChildLoadBalancingPolicy create(
        String childPolicyConfigTargetFieldName,
        List<Map<String, ?>> childPolicies) {
      Map<String, Object> effectiveChildPolicy = null;
      LoadBalancerProvider effectiveLbProvider = null;
      List<String> policyTried = new ArrayList<>();

      LoadBalancerRegistry lbRegistry = LoadBalancerRegistry.getDefaultRegistry();
      for (Map<String, ?> childPolicy : childPolicies) {
        if (childPolicy.isEmpty()) {
          continue;
        }
        String policyName = Iterables.getOnlyElement(childPolicy.keySet());
        LoadBalancerProvider provider = lbRegistry.getProvider(policyName);
        if (provider != null) {
          effectiveLbProvider = provider;
          effectiveChildPolicy = Collections.unmodifiableMap(childPolicy);
          break;
        }
        policyTried.add(policyName);
      }
      checkState(
          effectiveChildPolicy != null,
          "no valid childPolicy found, policy tried: %s", policyTried);
      checkArgument(effectiveChildPolicy.size() == 1, "??");
      return
          new ChildLoadBalancingPolicy(
              childPolicyConfigTargetFieldName,
              (Map<String, Object>) effectiveChildPolicy.values().iterator().next(),
              effectiveLbProvider);
    }

    /** Creates a load balancer config for given target. */
    public Map<String, ?> getEffectiveChildPolicy(String target) {
      Map<String, Object> childPolicy = new HashMap<>(effectiveRawChildPolicy);
      childPolicy.put(childPolicyConfigTargetFieldName, target);
      return childPolicy;
    }

    public LoadBalancerProvider getEffectiveLbProvider() {
      return effectiveLbProvider;
    }

    void addPendingRequest(RouteLookupRequest request, BackoffPolicy backoffPolicy) {
      checkNotNull(request, "request");
      checkNotNull(backoffPolicy, "backoffPolicy");
      BackoffPolicy existing = pendingRequests.put(request, backoffPolicy);
      checkState(existing == null, "This is a bug, can't have duplicated pending request");
    }

    void removePendingRequest(RouteLookupRequest request) {
      BackoffPolicy policy = pendingRequests.remove(request);
      checkState(policy != null, "This is a bug?");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ChildLoadBalancingPolicy that = (ChildLoadBalancingPolicy) o;
      return Objects.equals(effectiveRawChildPolicy, that.effectiveRawChildPolicy)
          && Objects.equals(effectiveLbProvider, that.effectiveLbProvider)
          && Objects.equals(childPolicyConfigTargetFieldName, that.childPolicyConfigTargetFieldName)
          && Objects.equals(pendingRequests, that.pendingRequests);
    }

    @Override
    public int hashCode() {
      return
          Objects
              .hash(
                  effectiveRawChildPolicy,
                  effectiveLbProvider,
                  childPolicyConfigTargetFieldName,
                  pendingRequests);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("effectiveRawChildPolicy", effectiveRawChildPolicy)
          .add("effectiveLbProvider", effectiveLbProvider)
          .add("childPolicyConfigTargetFieldName", childPolicyConfigTargetFieldName)
          .add("pendingRequests", pendingRequests)
          .toString();
    }
  }

  /**
   * ChildPolicyWrapper is a wrapper class for child load balancer with additional information to
   * keep track / manage / cache child policy.
   */
  static final class ChildPolicyWrapper implements Closeable {

    @VisibleForTesting
    static final Map<String /* target */, RefCountedObjectPool<ChildPolicyWrapper>>
        childPolicyMap = new HashMap<>();

    private final String target;
    private ChildLoadBalancingPolicy childPolicy;
    private LoadBalancer lb;
    private Subchannel subchannel;
    private ConnectivityState connectivityState = ConnectivityState.IDLE;
    private SubchannelPicker picker;
    private ChildLoadBalancerHelper helper;

    private ChildPolicyWrapper(String target) {
      this.target = target;
    }

    static ChildPolicyWrapper createOrGet(String target) {
      ObjectPool<ChildPolicyWrapper> existing = childPolicyMap.get(target);
      if (existing != null) {
        return existing.getObject();
      }
      ChildPolicyWrapper childPolicyWrapper = new ChildPolicyWrapper(target);
      RefCountedObjectPool<ChildPolicyWrapper> wrapper =
          RefCountedObjectPool.of(childPolicyWrapper);
      childPolicyMap.put(target, wrapper);
      return childPolicyWrapper;
    }

    String getTarget() {
      return target;
    }

    ChildLoadBalancingPolicy getChildPolicy() {
      return childPolicy;
    }

    void setChildPolicy(ChildLoadBalancingPolicy childPolicy) {
      this.childPolicy = childPolicy;
    }

    ConnectivityState getConnectivityState() {
      return connectivityState;
    }

    void setSubchannel(Subchannel subchannel) {
      this.subchannel = subchannel;
    }

    Subchannel getSubchannel() {
      return subchannel;
    }

    void setLoadBalancer(LoadBalancer lb) {
      this.lb = lb;
    }

    LoadBalancer getLoadBalancer() {
      return lb;
    }

    void setPicker(SubchannelPicker picker) {
      this.picker = checkNotNull(picker, "picker");
    }

    SubchannelPicker getPicker() {
      return picker;
    }

    public ChildLoadBalancerHelper getHelper() {
      return helper;
    }

    void setConnectivityState(ConnectivityState connectivityState) {
      this.connectivityState = connectivityState;
    }

    void release() {
      ObjectPool<ChildPolicyWrapper> existing = childPolicyMap.get(target);
      checkState(existing != null, "Cannot access already released object");
      if (existing.returnObject(this) == null) {
        childPolicyMap.remove(target);
      }
    }

    @Override
    public void close() {
      childPolicy = null;
      connectivityState = null;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ChildPolicyWrapper that = (ChildPolicyWrapper) o;
      return Objects.equals(target, that.target)
          && Objects.equals(childPolicy, that.childPolicy)
          && Objects.equals(lb, that.lb)
          && Objects.equals(subchannel, that.subchannel)
          && connectivityState == that.connectivityState
          && Objects.equals(picker, that.picker)
          && Objects.equals(helper, that.helper);
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, childPolicy, lb, subchannel, connectivityState, picker, helper);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("target", target)
          .add("childPolicy", childPolicy)
          .add("lb", lb)
          .add("subchannel", subchannel)
          .add("connectivityState", connectivityState)
          .add("picker", picker)
          .add("helper", helper)
          .toString();
    }
  }

  private static final class RefCountedObjectPool<T extends Closeable> implements ObjectPool<T> {

    private final AtomicLong refCnt = new AtomicLong(1);
    private T object;

    private RefCountedObjectPool(T object) {
      this.object = object;
    }

    @Override
    public T getObject() {
      long curr = refCnt.getAndIncrement();
      if (curr <= 0) {
        throw new IllegalStateException("Cannot access already released object");
      }
      return object;
    }

    @Override
    @Nullable
    public T returnObject(Object object) {
      checkState(this.object == object, "returned object doesn't match wrong object");
      long newCnt = refCnt.decrementAndGet();
      if (newCnt == 0) {
        try {
          this.object.close();
        } catch (IOException e) {
          throw new RuntimeException("Exception during close", e);
        }
        this.object = null;
      }
      return this.object;
    }

    static <T extends Closeable> RefCountedObjectPool<T> of(T t) {
      return new RefCountedObjectPool<>(t);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("object", object)
          .add("refCnt", refCnt.get())
          .toString();
    }
  }
}
