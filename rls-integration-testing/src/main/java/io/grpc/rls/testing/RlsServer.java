package io.grpc.rls.integration;

import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.lookup.v1.CacheRequest;
import io.grpc.lookup.v1.CachedRouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RlsServer {

  private static final ConcurrentHashMap<RouteLookupRequest, CacheRequest> cache
      = new ConcurrentHashMap<>();

  public static void main(String[] args) throws IOException, InterruptedException {
    Server server =
        NettyServerBuilder
            .forPort(8972)
            .addService(new RlsServerImpl(cache))
            .addService(new RlsCacheServerImpl(cache))
            .build();
    System.out.println("String RlsServer on port: 8972");
    server.start();
    server.awaitTermination();
  }

  public static final class RlsServerImpl extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private final ConcurrentHashMap<RouteLookupRequest, CacheRequest> cache;
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    public RlsServerImpl(
        ConcurrentHashMap<RouteLookupRequest, CacheRequest> cache) {
      this.cache = cache;
    }

    @Override
    public void routeLookup(final RouteLookupRequest request,
        final StreamObserver<RouteLookupResponse> responseObserver) {
      final CacheRequest value = cache.get(request);
      if (value == null) {
        System.out.println("###### not found for " + request);
        responseObserver.onError(new RuntimeException("not found"));
      } else {
        log("###### found", value);
        ses.schedule(
            new Runnable() {
              @Override
              public void run() {
                responseObserver.onNext(value.getResponse());
                responseObserver.onCompleted();
              }
            }, value.getLatency().getNanos(), TimeUnit.NANOSECONDS);
      }
    }
  }

  static void log(String message, CacheRequest value) {
    System.out.println(
        ">>>> " + message + "\nrequest: " + value.getRequest()
            + "\nresponse: " + value.getResponse()
            + "\ndelay: " + value.getLatency()
            + "\n===============================");
  }

  public static final class RlsCacheServerImpl
      extends CachedRouteLookupServiceGrpc.CachedRouteLookupServiceImplBase {

    private final ConcurrentHashMap<RouteLookupRequest, CacheRequest> cache;

    public RlsCacheServerImpl(
        ConcurrentHashMap<RouteLookupRequest, CacheRequest> cache) {
      this.cache = cache;
    }

    @Override
    public void registerReturnValue(
        CacheRequest request, StreamObserver<Empty> responseObserver) {
      CacheRequest oldVal = cache.put(request.getRequest(), request);
      if (oldVal != null) {
        log("removing cache", oldVal);
      }
      log("new cache", request);
      responseObserver.onNext(Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void invalidateCache(
        RouteLookupRequest request, StreamObserver<Empty> responseObserver) {
      CacheRequest removed = cache.remove(request);
      if (removed != null) {
        log("removing cache", removed);
      }
      responseObserver.onNext(Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
