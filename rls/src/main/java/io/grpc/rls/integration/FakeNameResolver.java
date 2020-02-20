package io.grpc.rls.integration;


import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.internal.GrpcAttributes;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;

public class FakeNameResolver extends NameResolver {

  @SuppressWarnings("unused")
  private final URI targetUri;
  @SuppressWarnings("unused")
  private final Args args;

  public FakeNameResolver(URI targetUri, Args args) {
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
        .setAddresses(Collections.singletonList(new EquivalentAddressGroup(new InetSocketAddress("localhost", 9999))))
        .setAttributes(
            Attributes.newBuilder()
                .set(
                    io.grpc.internal.GrpcAttributes.ATTR_LB_ADDRS,
                    ImmutableList.of(
                        new EquivalentAddressGroup(
                            new InetSocketAddress("localhost", 12345),
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
