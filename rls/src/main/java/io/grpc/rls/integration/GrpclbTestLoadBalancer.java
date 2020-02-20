package io.grpc.rls.integration;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import io.grpc.Server;
import io.grpc.lb.v1.InitialLoadBalanceRequest;
import io.grpc.lb.v1.InitialLoadBalanceResponse;
import io.grpc.lb.v1.LoadBalanceRequest;
import io.grpc.lb.v1.LoadBalanceResponse;
import io.grpc.lb.v1.LoadBalancerGrpc.LoadBalancerImplBase;
import io.grpc.lb.v1.ServerList;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GrpclbTestLoadBalancer {
  public static void main(String[] args) throws IOException, InterruptedException {
    // int port = 7777;
    int port = Integer.parseInt(args[0]);
   Server server =
        NettyServerBuilder
            .forPort(port)
            .addService(new TestLoadBalancerImpl())
            .build();
    try {
      server.start();
      System.out.println("Backend server available on port: " + port);
      server.awaitTermination();
    } finally {
      server.shutdownNow();
    }
  }

  public static final class TestLoadBalancerImpl extends LoadBalancerImplBase {

    @Override
    public StreamObserver<LoadBalanceRequest> balanceLoad(
        final StreamObserver<LoadBalanceResponse> responseObserver) {
      return new StreamObserver<LoadBalanceRequest>() {
        private String name = null;

        @Override
        public void onNext(LoadBalanceRequest value) {
          if (value.hasInitialRequest()) {
            InitialLoadBalanceRequest initialRequest = value.getInitialRequest();
            name = initialRequest.getName();
            responseObserver.onNext(
                LoadBalanceResponse.newBuilder()
                    .setInitialResponse(
                        InitialLoadBalanceResponse.newBuilder()
                            .setClientStatsReportInterval(Duration.newBuilder().setSeconds(3600).build())
                            .build())
                    .build());
          }
          checkState(name != null, "Missing initial request!");
          List<io.grpc.lb.v1.Server> servers = new ArrayList<>();
          servers.add(
              io.grpc.lb.v1.Server.newBuilder()
                  .setPort(9001)
                  .setIpAddress(ByteString.copyFrom(new byte[] {127, 0, 0, 1}))
                  .build());
          LoadBalanceResponse lbResponse =
              LoadBalanceResponse.newBuilder()
                  .setServerList(
                      ServerList.newBuilder()
                          .addAllServers(servers)
                          .build())
                  .build();
          System.out.println("LB response: " + lbResponse);
          responseObserver.onNext(lbResponse);

          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
          lbResponse =
              LoadBalanceResponse.newBuilder()
                  .setServerList(
                      ServerList.newBuilder().build())
                  .build();
          System.out.println("sending empty address");
          responseObserver.onNext(lbResponse);

          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }

          servers.clear();
          servers.add(
              io.grpc.lb.v1.Server.newBuilder()
                  .setPort(9001)
                  .setIpAddress(ByteString.copyFrom(new byte[] {127, 0, 0, 1}))
                  .build());
          lbResponse =
              LoadBalanceResponse.newBuilder()
                  .setServerList(
                      ServerList.newBuilder()
                          .addAllServers(servers)
                          .build())
                  .build();
          System.out.println("LB response: " + lbResponse);
          responseObserver.onNext(lbResponse);

        }

        @Override
        public void onError(Throwable t) {
          System.out.println("lb stream error: " + t.getMessage() + " for name: " + name);
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          System.out.println("LB stream completed: " + name);
          responseObserver.onCompleted();
        }
      };
    }
  }
}
