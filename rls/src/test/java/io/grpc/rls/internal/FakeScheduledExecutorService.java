package io.grpc.rls.internal;

import io.grpc.internal.TimeProvider;

public class FakeScheduledExecutorService {

  public TimeProvider getTimeProvider() {
    return null;
  }
}
