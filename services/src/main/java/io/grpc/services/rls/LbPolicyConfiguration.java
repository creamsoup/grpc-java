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

package io.grpc.services.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.List;
import java.util.Map;

public final class LbPolicyConfiguration {
  private final Map<String, ?> routeLookupConfig;
  private final List<Map<String, ?>> childPolicies;
  private final String childPolicyConfigTargetFieldName;

  LbPolicyConfiguration(
      Map<String, ?> routeLookupConfig,
      List<Map<String, ?>> childPolicies,
      String childPolicyConfigTargetFieldName) {
    this.routeLookupConfig = checkNotNull(routeLookupConfig, "routeLookupConfig");
    checkState(
        childPolicies != null && !childPolicies.isEmpty(),
        "childPolicies should be provided");
    this.childPolicies = childPolicies;
    checkState(
        childPolicyConfigTargetFieldName != null && !childPolicyConfigTargetFieldName.isEmpty(),
        "childPolicyConfigTargetFieldName should not be empty");
    this.childPolicyConfigTargetFieldName = childPolicyConfigTargetFieldName;
  }

  public static LbPolicyConfiguration from(Map<String, ?> rawLoadBalancingPolicyConfig)
      throws Exception {
    Map<String, ?> routeLookupConfig =
        getAsObject(rawLoadBalancingPolicyConfig, "route_lookup_config");
    List<Map<String, ?>> childPolicies = getAsList(rawLoadBalancingPolicyConfig, "child_policy");
    String childPolicyConfigTargetFieldName;

    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> getAsObject(Map<String, ?> jsonMap, String key) throws Exception {
    checkNotNull(key, "key");
    Object object = checkNotNull(jsonMap.get(key), key + " doesn't exist");
    return isJsonObject(key, object);
  }

  private static boolean isJsonObject(String key, Object object) {
    if (!(object instanceof Map)) {
      throw new IllegalArgumentException(
          String.format("Expected object type for key(%s), but got %s", key, object.getClass()));
    }
    for (Object name : ((Map) object).keySet()) {
      if (name instanceof String) {
        continue;
      }
      throw new IllegalArgumentException(
          String.format("Invalid json object, object key(%s) must be string", name));
    }
    return true;
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

  private static List<Map<String, ?>> getAsListOfObjects(Map<String, ?> jsonMap, String key) {
    checkNotNull(key, "key");
    List<Object> objects = getAsList(jsonMap, key);
    for (Object object : objects) {
      getAsObject()
    }

  }
}
