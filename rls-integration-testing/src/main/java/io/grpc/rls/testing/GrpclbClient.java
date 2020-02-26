package io.grpc.rls.integration;

import com.google.protobuf.Duration;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.internal.JsonParser;
import io.grpc.lookup.v1.BackendServiceGrpc;
import io.grpc.lookup.v1.BackendServiceGrpc.BackendServiceStub;
import io.grpc.lookup.v1.CacheRequest;
import io.grpc.lookup.v1.EchoRequest;
import io.grpc.lookup.v1.EchoResponse;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GrpclbClient {

  public static void main(String[] args) throws Exception {
    runClient();
  }

  static void runClient() throws Exception {
    // client will use localhost:12345 from RlsServer
    // fallback will be on 12346

    NameResolverRegistry.getDefaultRegistry().register(new FakeNameResolverProvider());
    final ManagedChannel clientChannel =
        NettyChannelBuilder
            // this will be ignored
            .forTarget("fake-rls:///localhost:8972")
            .defaultServiceConfig(getServiceConfig())
            .disableServiceConfigLookUp()
            .usePlaintext()
            .build();

    BackendServiceStub bStub = BackendServiceGrpc.newStub(clientChannel);
    final int iter = 30;
    final CountDownLatch latch = new CountDownLatch(iter);
    ConnectivityState prevState = ConnectivityState.IDLE;
    for (int i = 0; i < iter; i++) {
      System.out.println("iter " + i + " to backend1(9001)");
      final int iCopy = i;
      bStub.echo(EchoRequest.newBuilder().setMessage("message" + i).build(),
          new StreamObserver<EchoResponse>() {
            @Override
            public void onNext(EchoResponse value) {
              System.out.println("request " + iCopy + " to backend1(9001) -> " + value);
            }

            @Override
            public void onError(Throwable t) {
              System.out.println("oops error: " + t.getMessage());
              latch.countDown();
            }

            @Override
            public void onCompleted() {
              System.out.println("done! " + iCopy);
              latch.countDown();
            }
          });
      final ConnectivityState state = clientChannel.getState(false);
      if (state != prevState) {
        prevState = state;
        System.out.println("channel state: " + state);
        clientChannel.notifyWhenStateChanged(state, new Runnable() {
          @Override
          public void run() {
            System.out.println(
                "!! channel state changed from " + state + " to " + clientChannel.getState(false));
          }
        });
      }
      Thread.sleep(1000);
    }
    System.out.println("waiting for the responses");
    latch.await();
  }

  private static CacheRequest createCacheRequest(RouteLookupRequest request1,
      RouteLookupResponse response1, long latencyInMillis) {
    return
        CacheRequest.newBuilder()
            .setRequest(request1)
            .setResponse(response1)
            .setLatency(
                Duration.newBuilder()
                    .setNanos((int) TimeUnit.MILLISECONDS.toNanos(latencyInMillis))
                    .build())
            .build();
  }

  private static RouteLookupResponse createResponse(String target, String header) {
    return RouteLookupResponse.newBuilder().setTarget(target).setHeaderData(header).build();
  }

  private static RouteLookupRequest createRequest(String server, String path,
      Map<String, String> keyMap) {
    return
        RouteLookupRequest.newBuilder()
            .setPath(path)
            .setServer(server)
            .setTargetType("grpc")
            .putAllKeyMap(keyMap)
            .build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getServiceConfig() throws IOException {
    String grpclbJson = "{\"grpclb\": {\"childPolicy\": [{\"pick_first\": {}}]}}";
    String serviceConfig = "{\"loadBalancingConfig\": [" + grpclbJson + "  ]}";
    return (Map<String, Object>) JsonParser.parse(serviceConfig);
  }
}
