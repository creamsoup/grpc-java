package io.grpc.rls.testing;

import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;

public class ConstantRlsServer {

  /** Main. */
  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length != 2) {
      System.out.println("use format arg1=target, arg2=headerData");
      System.exit(1);
    }

    Server server =
        NettyServerBuilder
            .forPort(8972)
            .addService(new ConstantRlsServerImpl(args[0], args[1]))
            .build();
    server.start();
    System.out.println("String RlsServer on port: 8972");
    System.out.println(
        "ConstantRlsServer will return "
            + "target: " + args[0] + " headerData: " + args[1]);
    server.awaitTermination();
  }

  static final class ConstantRlsServerImpl
      extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private final String target;
    private final String headerData;

    public ConstantRlsServerImpl(String target, String headerData) {
      this.target = target;
      this.headerData = headerData;
    }

    @Override
    public void routeLookup(
        RouteLookupRequest request,
        StreamObserver<RouteLookupResponse> responseObserver) {
      System.out.println("<<< Received " + request);
      RouteLookupResponse response =
          RouteLookupResponse.newBuilder()
              .setTarget(target)
              .setHeaderData(headerData)
              .build();
      System.out.println(">>> Response " + response);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
