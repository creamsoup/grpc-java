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

import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;

public class RlsUtil {

  static EquivalentAddressGroup createEag(String target) {
    int index = target.indexOf(':');
    if (index < 0) {
      //TODO or 443
      return new EquivalentAddressGroup(new InetSocketAddress(target, 80));
    }
    return
        new EquivalentAddressGroup(
            new InetSocketAddress(
                target.substring(0, index),
                Integer.parseInt(target.substring(index + 1))));
  }

  private RlsUtil() {
  }
}
