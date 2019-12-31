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

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import java.util.concurrent.Executor;

interface RouteLookupClient {
  RouteLookupInfo routeLookup(RouteLookupRequest key);

  void shutdown();

  // maybe abstract class
  interface RouteLookupInfo extends ListenableFuture<RouteLookupResponse> {
    ChildPolicyWrapper getChildPolicyWrapper();
  }
}
