/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.rls.testing;

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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Simple echo backend. */
public final class Backend {

  /** Main. */
  public static void main(String[] args) throws IOException, InterruptedException {
    int port = Integer.parseInt(args[0]);
    Server server =
        NettyServerBuilder
            .forPort(port)
            .addService(new BackendImpl(port))
            .build();
    try {
      server.start();
      System.out.println("Backend server available on port: " + port);
      server.awaitTermination();
    } finally {
      server.shutdownNow();
    }
  }

  static final class BackendImpl extends BackendServiceGrpc.BackendServiceImplBase {

    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();
    private final int port;

    public BackendImpl(int port) {
      this.port = port;
    }

    @Override
    public void echo(
        final EchoRequest request, final StreamObserver<EchoResponse> responseObserver) {
      final int rand = random.nextInt(10);
      ScheduledFuture<?> unused = ses.schedule(new Runnable() {
        @Override
        public void run() {
          System.out.println("Responded in " + rand + " ms. req: " + request);
          responseObserver
              .onNext(
                  EchoResponse.newBuilder()
                      .setMessage(port + ": did you say '" + request.getMessage() + "'?")
                      .setOriginalMessage(request.getMessage())
                      .build());
          responseObserver.onCompleted();
        }
      }, rand, TimeUnit.MILLISECONDS);
    }
  }
}
