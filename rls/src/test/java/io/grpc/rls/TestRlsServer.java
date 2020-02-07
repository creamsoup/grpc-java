package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Converter;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

public class TestRlsServer extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

  private final AtomicReference<DelayedValueOrError> nextResponse =
      new AtomicReference<>();
  private final ScheduledExecutorService ses;
  private final Converter<RlsProtoData.RouteLookupResponse, RouteLookupResponse> respConverter =
      new RouteLookupResponseConverter().reverse();

  public TestRlsServer(ScheduledExecutorService ses) {
    this.ses = checkNotNull(ses, "ses");
  }

  @Override
  public void routeLookup(RouteLookupRequest request,
      final StreamObserver<RouteLookupResponse> responseObserver) {
    final DelayedValueOrError next = nextResponse.get();
    if (next == null) {
      throw new RuntimeException("must have nextResponse");
    }
    ses.schedule(new Runnable() {
      @Override
      public void run() {
        if (next.getValue() != null) {
          responseObserver.onNext(respConverter.convert(next.getValue()));
          responseObserver.onCompleted();
        } else {
          responseObserver.onError(next.getError());
        }
      }
    }, next.delayInMills, TimeUnit.MILLISECONDS);
  }

  public void setNextResponse(DelayedValueOrError delayedValueOrError) {
    nextResponse.set(delayedValueOrError);
  }

  public static final class DelayedValueOrError {

    @Nullable
    private final RlsProtoData.RouteLookupResponse value;
    @Nullable
    private final Exception error;
    private final long delayInMills;

    private DelayedValueOrError(
        @Nullable RlsProtoData.RouteLookupResponse value,
        @Nullable Exception error,
        long delayInMills) {
      checkState(value == null ^ error == null, "only value or error should be specified");
      this.value = value;
      this.error = error;
      checkState(delayInMills >= 0, "delay must be non negative");
      this.delayInMills = delayInMills;
    }

    @Nullable
    public RlsProtoData.RouteLookupResponse getValue() {
      return value;
    }

    @Nullable
    public Exception getError() {
      return error;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DelayedValueOrError that = (DelayedValueOrError) o;
      return delayInMills == that.delayInMills &&
          Objects.equals(value, that.value) &&
          Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, error, delayInMills);
    }

    public static DelayedValueOrError forError(Exception t, long delayInMills) {
      return new DelayedValueOrError(null, t, delayInMills);
    }

    public static DelayedValueOrError forValue(RlsProtoData.RouteLookupResponse value, long delayInMills) {
      return new DelayedValueOrError(value, null, delayInMills);
    }
  }
}
