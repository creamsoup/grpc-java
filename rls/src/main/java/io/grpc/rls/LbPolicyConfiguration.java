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

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.AtomicBackoff;
import io.grpc.internal.ObjectPool;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A LbPolicyConfiguration is configuration for RLS to delegate request to other LB implementations.
 */
final class LbPolicyConfiguration {

  private final RouteLookupConfig routeLookupConfig;
  private final LoadBalancingPolicy policy;

  public LbPolicyConfiguration(RouteLookupConfig routeLookupConfig, LoadBalancingPolicy policy) {
    this.routeLookupConfig = checkNotNull(routeLookupConfig, "routeLookupConfig");
    this.policy = checkNotNull(policy, "policy");
  }

  public RouteLookupConfig getRouteLookupConfig() {
    return routeLookupConfig;
  }

  public LoadBalancingPolicy getLoadBalancingPolicy() {
    return policy;
  }

  static final class ChildPolicyWrapper implements Closeable {
    private static final Map<String /* target */, ObjectPool<ChildPolicyWrapper>> childPolicyMap =
        new HashMap<>();
    private final String target;
    private LoadBalancingPolicy childPolicy;
    private ConnectivityState connectivityState;
    private Subchannel subchannel;
    private SubchannelPicker picker = new PendingPicker();

    private ChildPolicyWrapper(String target) {
      this.target = target;
    }

    public static ObjectPool<ChildPolicyWrapper> createOrGet(String target) {
      ObjectPool<ChildPolicyWrapper> existing = childPolicyMap.get(target);
      if (existing != null) {
        return existing;
      }
      ChildPolicyWrapper childPolicyWrapper = new ChildPolicyWrapper(target);
      RefCountedObjectPool<ChildPolicyWrapper> wrapper =
          RefCountedObjectPool.of(childPolicyWrapper);
      childPolicyMap.put(target, wrapper);
      return wrapper;
    }

    public String getTarget() {
      return target;
    }

    public LoadBalancingPolicy getChildPolicy() {
      return childPolicy;
    }

    public void setChildPolicy(LoadBalancingPolicy childPolicy) {
      this.childPolicy = childPolicy;
    }

    public ConnectivityState getConnectivityState() {
      return connectivityState;
    }

    public void setSubchannel(Subchannel subchannel) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
    }

    public void setConnectivityState(ConnectivityState connectivityState) {
      System.out.println("connectivity changed");
      this.connectivityState = connectivityState;
      if (connectivityState == ConnectivityState.READY) {
        checkState(subchannel != null, "???");
        System.out.println("setting ReadyPicker");
        picker = new ReadyPicker(subchannel);
      }
    }

    public SubchannelPicker getPicker() {
      return picker;
    }

    public ChildPolicyWrapper acquire() {
      return childPolicyMap.get(target).getObject();
    }

    public void release() {
      ObjectPool<ChildPolicyWrapper> existing = childPolicyMap.get(target);
      checkState(existing != null, "doesn't exists!");
      existing.returnObject(this);
    }

    @Override
    public void close() {
      // this might be error prone, if closed is called out side of release.
      childPolicy = null;
      connectivityState = null;
      picker = null;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ChildPolicyWrapper wrapper = (ChildPolicyWrapper) o;
      return Objects.equal(target, wrapper.target)
          && Objects.equal(childPolicy, wrapper.childPolicy)
          && connectivityState == wrapper.connectivityState
          && Objects.equal(picker, wrapper.picker);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(childPolicyMap, target, childPolicy, connectivityState, picker);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("childPolicyMap", childPolicyMap)
          .add("target", target)
          .add("childPolicy", childPolicy)
          .add("connectivityState", connectivityState)
          .add("picker", picker)
          .toString();
    }

    private static final class PendingPicker extends SubchannelPicker {

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withNoResult();
      }
    }

    private static final class ReadyPicker extends SubchannelPicker {

      private final Subchannel subchannel;

      public ReadyPicker(Subchannel subchannel) {
        super();
        this.subchannel = subchannel;
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel);
      }
    }
  }

  static final class LoadBalancingPolicy {

    private final RouteLookupConfig routeLookupConfig;
    private final Map<String, ?> effectiveChildPolicy;
    private final LoadBalancerProvider effectiveLbProvider;
    private final String childPolicyConfigTargetFieldName;

    // path to Currently pending RLS requests.
    private final ConcurrentMap<String /* path */, PendingRlsRequest> pendingRequests
        = new ConcurrentHashMap<>();

    public LoadBalancingPolicy(
        RouteLookupConfig routeLookupConfig,
        String childPolicyConfigTargetFieldName,
        List<Map<String, ?>> childPolicies) {
      this.routeLookupConfig = checkNotNull(routeLookupConfig, "routeLookupConfig");
      checkState(
          childPolicyConfigTargetFieldName != null && !childPolicyConfigTargetFieldName.isEmpty(),
          "childPolicyConfigTargetFieldName cannot be empty or null");
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
      checkState(
          effectiveChildPolicy != null,
          "no valid childPolicy found, policy tried: %s", policyTried);
      this.effectiveChildPolicy = effectiveChildPolicy;
      this.effectiveLbProvider = effectiveLbProvider;
    }

    public String getChildPolicyConfigTargetFieldName() {
      return childPolicyConfigTargetFieldName;
    }

    public Map<String, ?> getEffectiveChildPolicy(String target) {
      checkArgument(
          routeLookupConfig.getValidTargets().contains(target),
          "target(%s) must be present in RouteLookupConfig.validTargets", target);
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

    static class PendingRlsRequest {
      // state for the pending RLS request (will be only pending)?????
      // should it contain list of requests???
      AtomicBackoff backoff; // backoff status (doesn't mean it is backoff status)
    }
  }

  private static class RefCountedObjectPool<T extends Closeable> implements ObjectPool<T> {

    private final AtomicLong refCnt = new AtomicLong(1);
    private T t;

    private RefCountedObjectPool(T t) {
      this.t = t;
    }

    @Override
    public T getObject() {
      long curr = refCnt.getAndIncrement();
      if (curr <= 0) {
        throw new IllegalStateException("already released");
      }
      return t;
    }

    @Override
    public T returnObject(Object object) {
      checkState(t == object, "returned wrong object");
      long newCnt = refCnt.decrementAndGet();
      if (newCnt == 0) {
        try {
          t.close();
        } catch (IOException e) {
          throw new RuntimeException("error during close", e);
        }
        return null;
      }
      return t;
    }

    public static <T extends Closeable> RefCountedObjectPool<T> of(T t) {
      return new RefCountedObjectPool<>(t);
    }
  }
}
