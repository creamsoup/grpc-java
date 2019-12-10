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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.concurrent.Immutable;

@Immutable
final class RlsLbPolicyConfiguration {

  // TODO create an object version of this
  private final Map<String, ?> routeLookupConfig;
  // TODO see https://github.com/grpc/proposal/blob/master/A24-lb-policy-config.md
  private final List<Map<String, ?>> childPolicies;
  private final String childPolicyConfigTargetFieldName;

  RlsLbPolicyConfiguration(
      Map<String, ?> routeLookupConfig,
      List<Map<String, ?>> childPolicies,
      String childPolicyConfigTargetFieldName) {
    this.routeLookupConfig =
        Collections.unmodifiableMap(checkNotNull(routeLookupConfig, "routeLookupConfig"));
    checkState(
        childPolicies != null && !childPolicies.isEmpty(),
        "childPolicies should be provided");
    List<Map<String, ?>> unmodifiableChildPolicy = new ArrayList<>();
    for (Map<String, ?> childPolicy : childPolicies) {
      unmodifiableChildPolicy.add(Collections.unmodifiableMap(childPolicy));
    }
    this.childPolicies = Collections.unmodifiableList(unmodifiableChildPolicy);
    checkState(
        childPolicyConfigTargetFieldName != null && !childPolicyConfigTargetFieldName.isEmpty(),
        "childPolicyConfigTargetFieldName should not be empty");
    this.childPolicyConfigTargetFieldName = childPolicyConfigTargetFieldName;
  }

  public Map<String, ?> getRouteLookupConfig() {
    return routeLookupConfig;
  }

  public List<Map<String, ?>> getChildPolicies() {
    return childPolicies;
  }

  public String getChildPolicyConfigTargetFieldName() {
    return childPolicyConfigTargetFieldName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RlsLbPolicyConfiguration that = (RlsLbPolicyConfiguration) o;
    return Objects.equal(routeLookupConfig, that.routeLookupConfig)
        && Objects.equal(childPolicies, that.childPolicies)
        && Objects
        .equal(childPolicyConfigTargetFieldName, that.childPolicyConfigTargetFieldName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(routeLookupConfig, childPolicies, childPolicyConfigTargetFieldName);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("routeLookupConfig", routeLookupConfig)
        .add("childPolicies", childPolicies)
        .add("childPolicyConfigTargetFieldName", childPolicyConfigTargetFieldName)
        .toString();
  }

  public static RlsLbPolicyConfiguration from(Map<String, ?> rawLoadBalancingPolicyConfig) {
    Map<String, ?> routeLookupConfig =
        getAsObject(rawLoadBalancingPolicyConfig, "route_lookup_config");
    List<Map<String, ?>> childPolicies =
        getAsListOfObjects(rawLoadBalancingPolicyConfig, "child_policy");
    String childPolicyConfigTargetFieldName =
        (String) rawLoadBalancingPolicyConfig.get("child_policy_config_target_field_name");

    return new RlsLbPolicyConfiguration(
        routeLookupConfig, childPolicies, childPolicyConfigTargetFieldName);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> getAsObject(Map<String, ?> jsonMap, String key) {
    checkNotNull(key, "key");
    Object object = checkNotNull(jsonMap.get(key), key + " doesn't exist");
    checkState(
        isJsonObject(object), "value(%s) for key: %s is not an json object format", object, key);
    return (Map<String, ?>) object;
  }

  private static boolean isJsonObject(Object object) {
    if (!(object instanceof Map)) {
      return false;
    }
    for (Object name : ((Map) object).keySet()) {
      if (name instanceof String) {
        continue;
      }
      return false;
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, ?>> getAsListOfObjects(Map<String, ?> jsonMap, String key) {
    checkNotNull(key, "key");
    List<Map<String, ?>> objects = new ArrayList<>();

    for (Object object : getAsList(jsonMap, key)) {
      if (!isJsonObject(object)) {
        throw new IllegalArgumentException(
            "Expected object type but got " + object.getClass().getSimpleName());
      }
      objects.add((Map<String, ?>) object);
    }
    return objects;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> getAsList(Map<String, ?> jsonMap, String key) {
    checkNotNull(key, "key");
    Object object = checkNotNull(jsonMap.get(key), key + " doesn't exist");
    if (!(object instanceof List)) {
      throw new IllegalArgumentException(
          String.format("Expected list type for key(%s), but got %s", key, object.getClass()));
    }
    return (List<Object>) object;
  }
}
