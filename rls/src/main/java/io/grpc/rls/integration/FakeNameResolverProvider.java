package io.grpc.rls.integration;

import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import java.net.URI;

public class FakeNameResolverProvider extends NameResolverProvider {

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
    return new FakeNameResolver(targetUri, args);
  }
}
