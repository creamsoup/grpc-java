package io.grpc.lookup.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/lookup/v1/rls_test.proto")
public final class BackendServiceGrpc {

  private BackendServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.lookup.v1.BackendService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.lookup.v1.EchoRequest,
      io.grpc.lookup.v1.EchoResponse> getEchoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Echo",
      requestType = io.grpc.lookup.v1.EchoRequest.class,
      responseType = io.grpc.lookup.v1.EchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.lookup.v1.EchoRequest,
      io.grpc.lookup.v1.EchoResponse> getEchoMethod() {
    io.grpc.MethodDescriptor<io.grpc.lookup.v1.EchoRequest, io.grpc.lookup.v1.EchoResponse> getEchoMethod;
    if ((getEchoMethod = BackendServiceGrpc.getEchoMethod) == null) {
      synchronized (BackendServiceGrpc.class) {
        if ((getEchoMethod = BackendServiceGrpc.getEchoMethod) == null) {
          BackendServiceGrpc.getEchoMethod = getEchoMethod =
              io.grpc.MethodDescriptor.<io.grpc.lookup.v1.EchoRequest, io.grpc.lookup.v1.EchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Echo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lookup.v1.EchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lookup.v1.EchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BackendServiceMethodDescriptorSupplier("Echo"))
              .build();
        }
      }
    }
    return getEchoMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static BackendServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BackendServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BackendServiceStub>() {
        @java.lang.Override
        public BackendServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BackendServiceStub(channel, callOptions);
        }
      };
    return BackendServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static BackendServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BackendServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BackendServiceBlockingStub>() {
        @java.lang.Override
        public BackendServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BackendServiceBlockingStub(channel, callOptions);
        }
      };
    return BackendServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static BackendServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BackendServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BackendServiceFutureStub>() {
        @java.lang.Override
        public BackendServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BackendServiceFutureStub(channel, callOptions);
        }
      };
    return BackendServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class BackendServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void echo(io.grpc.lookup.v1.EchoRequest request,
        io.grpc.stub.StreamObserver<io.grpc.lookup.v1.EchoResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getEchoMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEchoMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.lookup.v1.EchoRequest,
                io.grpc.lookup.v1.EchoResponse>(
                  this, METHODID_ECHO)))
          .build();
    }
  }

  /**
   */
  public static final class BackendServiceStub extends io.grpc.stub.AbstractAsyncStub<BackendServiceStub> {
    private BackendServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BackendServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BackendServiceStub(channel, callOptions);
    }

    /**
     */
    public void echo(io.grpc.lookup.v1.EchoRequest request,
        io.grpc.stub.StreamObserver<io.grpc.lookup.v1.EchoResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getEchoMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class BackendServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<BackendServiceBlockingStub> {
    private BackendServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BackendServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BackendServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.lookup.v1.EchoResponse echo(io.grpc.lookup.v1.EchoRequest request) {
      return blockingUnaryCall(
          getChannel(), getEchoMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class BackendServiceFutureStub extends io.grpc.stub.AbstractFutureStub<BackendServiceFutureStub> {
    private BackendServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BackendServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BackendServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.lookup.v1.EchoResponse> echo(
        io.grpc.lookup.v1.EchoRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getEchoMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ECHO = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final BackendServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(BackendServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ECHO:
          serviceImpl.echo((io.grpc.lookup.v1.EchoRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.lookup.v1.EchoResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class BackendServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    BackendServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.lookup.v1.RlsTestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("BackendService");
    }
  }

  private static final class BackendServiceFileDescriptorSupplier
      extends BackendServiceBaseDescriptorSupplier {
    BackendServiceFileDescriptorSupplier() {}
  }

  private static final class BackendServiceMethodDescriptorSupplier
      extends BackendServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    BackendServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (BackendServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new BackendServiceFileDescriptorSupplier())
              .addMethod(getEchoMethod())
              .build();
        }
      }
    }
    return result;
  }
}
