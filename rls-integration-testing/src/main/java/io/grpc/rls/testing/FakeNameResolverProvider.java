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

package io.grpc.rls.testing;

import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import java.net.URI;

/** Fake testing name resolver provider. */
public final class FakeNameResolverProvider extends NameResolverProvider {

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 0;
  }

  @Override
  public String getDefaultScheme() {
    return "fake-rls";
  }

  @Override
  public NameResolver newNameResolver(URI targetUri, Args args) {
    if ("fake-rls".equals(targetUri.getScheme())) {
      return new FakeNameResolver(targetUri, args);
    }
    return null;
  }
}
