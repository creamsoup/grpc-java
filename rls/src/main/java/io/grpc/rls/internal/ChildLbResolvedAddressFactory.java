package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.ResolvedAddresses;
import java.util.Collections;
import java.util.List;

/** Factory to created {@link io.grpc.LoadBalancer.ResolvedAddresses} passed to child lb. */
public final class ChildLbResolvedAddressFactory {

  private final List<EquivalentAddressGroup> addresses;
  private final Attributes attributes;

  public ChildLbResolvedAddressFactory(
      List<EquivalentAddressGroup> addresses, Attributes attributes) {
    checkArgument(addresses != null && !addresses.isEmpty(), "Address must be provided");
    this.addresses = Collections.unmodifiableList(addresses);
    this.attributes = checkNotNull(attributes, "attributes");
  }

  public ResolvedAddresses create(Object childLbConfig) {
    return ResolvedAddresses.newBuilder()
        .setAddresses(addresses)
        .setAttributes(attributes)
        .setLoadBalancingPolicyConfig(childLbConfig)
        .build();
  }
}
