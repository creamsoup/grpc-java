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


import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.internal.GrpcAttributes;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;

/** Fake name resolver to test. */
public final class FakeNameResolver extends NameResolver {

  @SuppressWarnings("unused")
  private final URI targetUri;
  @SuppressWarnings("unused")
  private final Args args;

  FakeNameResolver(URI targetUri, Args args) {
    System.out.println("FAKE NR: " + targetUri);
    this.targetUri = targetUri;
    this.args = args;
  }

  @Override
  public String getServiceAuthority() {
    return "fake-rls";
  }

  @Override
  @SuppressWarnings("deprecation")
  public void start(Listener2 listener) {
    listener.onResult(ResolutionResult.newBuilder()
        // .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAddresses(
            Collections.singletonList(
                new EquivalentAddressGroup(new InetSocketAddress("localhost", 9999))))
        .setAttributes(
            Attributes.newBuilder()
                .set(
                    io.grpc.internal.GrpcAttributes.ATTR_LB_ADDRS,
                    ImmutableList.of(
                        new EquivalentAddressGroup(
                            // new InetSocketAddress("localhost", 7777), // for grpclb
                            new InetSocketAddress("localhost", 12345), // for rls
                            Attributes.newBuilder()
                                .set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "localhost")
                                .build())))

              .build())
        .build());
  }

  @Override
  public void shutdown() {
  }
}
