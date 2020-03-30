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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import io.grpc.ConnectivityState;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Implementation of {@link SubchannelStateManager}. */
final class SubchannelStateManagerImpl implements SubchannelStateManager {

  private final ConcurrentHashMap<String, ConnectivityState> stateMap = new ConcurrentHashMap<>();
  private final Multiset<ConnectivityState> stateMultiset = ConcurrentHashMultiset.create();

  @Override
  public void registerNewState(String name, ConnectivityState newState) {
    ConnectivityState existing;
    if (newState == ConnectivityState.SHUTDOWN) {
      existing = stateMap.remove(name);
    } else {
      existing = stateMap.put(checkNotNull(name, "name"), checkNotNull(newState, "newState"));
      stateMultiset.add(newState);
    }
    if (existing != null) {
      stateMultiset.remove(existing);
    }
  }

  @Override
  @Nullable
  public ConnectivityState getState(String name) {
    return stateMap.get(checkNotNull(name, "name"));
  }

  @Override
  public ConnectivityState getAggregatedState() {
    if (stateMultiset.contains(ConnectivityState.READY)) {
      return ConnectivityState.READY;
    } else if (stateMultiset.contains(ConnectivityState.CONNECTING)) {
      return ConnectivityState.CONNECTING;
    } else if (stateMultiset.contains(ConnectivityState.IDLE)) {
      return ConnectivityState.IDLE;
    } else if (stateMultiset.contains(ConnectivityState.TRANSIENT_FAILURE)) {
      return ConnectivityState.TRANSIENT_FAILURE;
    }
    // empty or shutdown
    return ConnectivityState.IDLE;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("stateMap", stateMap)
        .toString();
  }
}