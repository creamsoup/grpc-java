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

public class TestLoadBalancer {
  public static void main(String[] args) throws IOException, InterruptedException {
    // int port = 12345;
    int port = Integer.parseInt(args[0]);
    Multimap<String, Pair<byte[], Integer>> targetToBackends = LinkedListMultimap.create();
    targetToBackends.put("backend9001", new Pair<>(new byte[] {127, 0, 0, 1}, 9001));
    targetToBackends.put("backend9002and3", new Pair<>(new byte[] {127, 0, 0, 1}, 9002));
    targetToBackends.put("backend9002and3", new Pair<>(new byte[] {127, 0, 0, 1}, 9003));
    Server server =
        NettyServerBuilder
            .forPort(port)
            .addService(new TestLoadBalancerImpl(targetToBackends))
            .build();
    try {
      server.start();
      System.out.println("Backend server available on port: " + port);
      server.awaitTermination();
    } finally {
      server.shutdownNow();
    }
  }

  public static final class Pair<A, B> {
    A a;
    B b;

    public Pair(A a, B b) {
      this.a = a;
      this.b = b;
    }
  }

  public static final class TestLoadBalancerImpl extends LoadBalancerImplBase {
    private final Multimap<String, Pair<byte[], Integer>> backendsAddressesForTarget;

    public TestLoadBalancerImpl(
        Multimap<String, Pair<byte[], Integer>> backendsAddressesForTarget) {
      this.backendsAddressesForTarget = backendsAddressesForTarget;
    }

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
          for (Pair<byte[], Integer> s : backendsAddressesForTarget.get(name)) {
            servers.add(
                io.grpc.lb.v1.Server.newBuilder()
                    .setIpAddress(ByteString.copyFrom(s.a))
                    .setLoadBalanceToken("token1")
                    .setPort(s.b)
                    .build());
          }
          LoadBalanceResponse lbResponse =
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
