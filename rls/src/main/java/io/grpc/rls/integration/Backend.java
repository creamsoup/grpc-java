package io.grpc.rls.integration;

import io.grpc.Server;
import io.grpc.lookup.v1.BackendServiceGrpc;
import io.grpc.lookup.v1.EchoRequest;
import io.grpc.lookup.v1.EchoResponse;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Backend {

  public static void main(String[] args) throws IOException, InterruptedException {
    int port = Integer.parseInt(args[0]);
    Server server =
        NettyServerBuilder
            .forPort(port)
            .addService(new BackendImpl())
            .build();
    try {
      server.start();
      System.out.println("Backend server available on port: " + port);
      server.awaitTermination();
    } finally {
      server.shutdownNow();
    }
  }

  public static final class BackendImpl extends BackendServiceGrpc.BackendServiceImplBase {

    private final ScheduledExecutorService ses =Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();

    @Override
    public void echo(final EchoRequest request, final StreamObserver<EchoResponse> responseObserver) {
      int rand = random.nextInt(10);
      System.out.println("Response in " + rand + " ms.");
      ses.schedule(new Runnable() {
        @Override
        public void run() {
          responseObserver
              .onNext(
                  EchoResponse.newBuilder()
                      .setMessage("did you say '" + request.getMessage() + "'?")
                      .setOriginalMessage(request.getMessage())
                      .build());
          responseObserver.onCompleted();
        }
      }, rand, TimeUnit.MILLISECONDS);

    }
  }
}
