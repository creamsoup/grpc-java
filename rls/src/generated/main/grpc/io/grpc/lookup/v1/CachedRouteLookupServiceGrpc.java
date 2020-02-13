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
    comments = "Source: grpc/rls/v1/rls_test.proto")
public final class CachedRouteLookupServiceGrpc {

  private CachedRouteLookupServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.lookup.v1.CachedRouteLookupService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.lookup.v1.CacheRequest,
      com.google.protobuf.Empty> getRegisterReturnValueMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterReturnValue",
      requestType = io.grpc.lookup.v1.CacheRequest.class,
      responseType = com.google.protobuf.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.lookup.v1.CacheRequest,
      com.google.protobuf.Empty> getRegisterReturnValueMethod() {
    io.grpc.MethodDescriptor<io.grpc.lookup.v1.CacheRequest, com.google.protobuf.Empty> getRegisterReturnValueMethod;
    if ((getRegisterReturnValueMethod = CachedRouteLookupServiceGrpc.getRegisterReturnValueMethod) == null) {
      synchronized (CachedRouteLookupServiceGrpc.class) {
        if ((getRegisterReturnValueMethod = CachedRouteLookupServiceGrpc.getRegisterReturnValueMethod) == null) {
          CachedRouteLookupServiceGrpc.getRegisterReturnValueMethod = getRegisterReturnValueMethod =
              io.grpc.MethodDescriptor.<io.grpc.lookup.v1.CacheRequest, com.google.protobuf.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterReturnValue"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lookup.v1.CacheRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new CachedRouteLookupServiceMethodDescriptorSupplier("RegisterReturnValue"))
              .build();
        }
      }
    }
    return getRegisterReturnValueMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.lookup.v1.RouteLookupRequest,
      com.google.protobuf.Empty> getInvalidateCacheMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InvalidateCache",
      requestType = io.grpc.lookup.v1.RouteLookupRequest.class,
      responseType = com.google.protobuf.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.lookup.v1.RouteLookupRequest,
      com.google.protobuf.Empty> getInvalidateCacheMethod() {
    io.grpc.MethodDescriptor<io.grpc.lookup.v1.RouteLookupRequest, com.google.protobuf.Empty> getInvalidateCacheMethod;
    if ((getInvalidateCacheMethod = CachedRouteLookupServiceGrpc.getInvalidateCacheMethod) == null) {
      synchronized (CachedRouteLookupServiceGrpc.class) {
        if ((getInvalidateCacheMethod = CachedRouteLookupServiceGrpc.getInvalidateCacheMethod) == null) {
          CachedRouteLookupServiceGrpc.getInvalidateCacheMethod = getInvalidateCacheMethod =
              io.grpc.MethodDescriptor.<io.grpc.lookup.v1.RouteLookupRequest, com.google.protobuf.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InvalidateCache"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lookup.v1.RouteLookupRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new CachedRouteLookupServiceMethodDescriptorSupplier("InvalidateCache"))
              .build();
        }
      }
    }
    return getInvalidateCacheMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static CachedRouteLookupServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CachedRouteLookupServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CachedRouteLookupServiceStub>() {
        @java.lang.Override
        public CachedRouteLookupServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CachedRouteLookupServiceStub(channel, callOptions);
        }
      };
    return CachedRouteLookupServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static CachedRouteLookupServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CachedRouteLookupServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CachedRouteLookupServiceBlockingStub>() {
        @java.lang.Override
        public CachedRouteLookupServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CachedRouteLookupServiceBlockingStub(channel, callOptions);
        }
      };
    return CachedRouteLookupServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static CachedRouteLookupServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CachedRouteLookupServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CachedRouteLookupServiceFutureStub>() {
        @java.lang.Override
        public CachedRouteLookupServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CachedRouteLookupServiceFutureStub(channel, callOptions);
        }
      };
    return CachedRouteLookupServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class CachedRouteLookupServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void registerReturnValue(io.grpc.lookup.v1.CacheRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      asyncUnimplementedUnaryCall(getRegisterReturnValueMethod(), responseObserver);
    }

    /**
     */
    public void invalidateCache(io.grpc.lookup.v1.RouteLookupRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      asyncUnimplementedUnaryCall(getInvalidateCacheMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getRegisterReturnValueMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.lookup.v1.CacheRequest,
                com.google.protobuf.Empty>(
                  this, METHODID_REGISTER_RETURN_VALUE)))
          .addMethod(
            getInvalidateCacheMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.lookup.v1.RouteLookupRequest,
                com.google.protobuf.Empty>(
                  this, METHODID_INVALIDATE_CACHE)))
          .build();
    }
  }

  /**
   */
  public static final class CachedRouteLookupServiceStub extends io.grpc.stub.AbstractAsyncStub<CachedRouteLookupServiceStub> {
    private CachedRouteLookupServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CachedRouteLookupServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CachedRouteLookupServiceStub(channel, callOptions);
    }

    /**
     */
    public void registerReturnValue(io.grpc.lookup.v1.CacheRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getRegisterReturnValueMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void invalidateCache(io.grpc.lookup.v1.RouteLookupRequest request,
        io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getInvalidateCacheMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class CachedRouteLookupServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<CachedRouteLookupServiceBlockingStub> {
    private CachedRouteLookupServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CachedRouteLookupServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CachedRouteLookupServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.google.protobuf.Empty registerReturnValue(io.grpc.lookup.v1.CacheRequest request) {
      return blockingUnaryCall(
          getChannel(), getRegisterReturnValueMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.google.protobuf.Empty invalidateCache(io.grpc.lookup.v1.RouteLookupRequest request) {
      return blockingUnaryCall(
          getChannel(), getInvalidateCacheMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class CachedRouteLookupServiceFutureStub extends io.grpc.stub.AbstractFutureStub<CachedRouteLookupServiceFutureStub> {
    private CachedRouteLookupServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CachedRouteLookupServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CachedRouteLookupServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> registerReturnValue(
        io.grpc.lookup.v1.CacheRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getRegisterReturnValueMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.Empty> invalidateCache(
        io.grpc.lookup.v1.RouteLookupRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getInvalidateCacheMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REGISTER_RETURN_VALUE = 0;
  private static final int METHODID_INVALIDATE_CACHE = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final CachedRouteLookupServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(CachedRouteLookupServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_REGISTER_RETURN_VALUE:
          serviceImpl.registerReturnValue((io.grpc.lookup.v1.CacheRequest) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
          break;
        case METHODID_INVALIDATE_CACHE:
          serviceImpl.invalidateCache((io.grpc.lookup.v1.RouteLookupRequest) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.Empty>) responseObserver);
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

  private static abstract class CachedRouteLookupServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    CachedRouteLookupServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.lookup.v1.RlsTestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("CachedRouteLookupService");
    }
  }

  private static final class CachedRouteLookupServiceFileDescriptorSupplier
      extends CachedRouteLookupServiceBaseDescriptorSupplier {
    CachedRouteLookupServiceFileDescriptorSupplier() {}
  }

  private static final class CachedRouteLookupServiceMethodDescriptorSupplier
      extends CachedRouteLookupServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    CachedRouteLookupServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (CachedRouteLookupServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new CachedRouteLookupServiceFileDescriptorSupplier())
              .addMethod(getRegisterReturnValueMethod())
              .addMethod(getInvalidateCacheMethod())
              .build();
        }
      }
    }
    return result;
  }
}
