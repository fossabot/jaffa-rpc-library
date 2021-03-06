package com.jaffa.rpc.lib.grpc.receivers;

import com.google.protobuf.ByteString;
import com.jaffa.rpc.grpc.services.*;
import com.jaffa.rpc.lib.common.Options;
import com.jaffa.rpc.lib.common.RequestInvoker;
import com.jaffa.rpc.lib.entities.Command;
import com.jaffa.rpc.lib.exception.JaffaRpcExecutionException;
import com.jaffa.rpc.lib.exception.JaffaRpcSystemException;
import com.jaffa.rpc.lib.grpc.MessageConverterHelper;
import com.jaffa.rpc.lib.zookeeper.Utils;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class GrpcAsyncAndSyncRequestReceiver implements Runnable, Closeable {

    private static final ExecutorService asyncService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static final ExecutorService requestService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static final Map<Pair<String, Integer>, ManagedChannel> cache = new ConcurrentHashMap<>();

    private Server server;

    public static void shutDownChannels() {
        cache.values().forEach(x -> {
            if (!x.isShutdown()) x.shutdownNow();
        });
        log.info("All gRPC async reply channels were terminated");
    }

    public static NettyServerBuilder addSecurityContext(NettyServerBuilder serverBuilder) {
        try {
            if (Boolean.parseBoolean(System.getProperty(Options.GRPC_USE_SSL, "false"))) {
                return serverBuilder.sslContext(GrpcSslContexts.
                        configure(SslContextBuilder.
                                forServer(new File(Utils.getRequiredOption(Options.GRPC_SSL_SERVER_STORE_LOCATION)),
                                        new File(Utils.getRequiredOption(Options.GRPC_SSL_SERVER_KEY_LOCATION)))).build());
            } else {
                return serverBuilder;
            }
        } catch (SSLException sslException) {
            log.error("Exception occurred while creating SSL context for gRPC", sslException);
            throw new JaffaRpcSystemException(sslException);
        }
    }

    public static NettyChannelBuilder addSecurityContext(NettyChannelBuilder channelBuilder) {
        try {
            if (Boolean.parseBoolean(System.getProperty(Options.GRPC_USE_SSL, "false"))) {
                return channelBuilder.sslContext(GrpcSslContexts.
                        configure(SslContextBuilder.forClient().
                                keyManager(new File(Utils.getRequiredOption(Options.GRPC_SSL_CLIENT_KEYSTORE_LOCATION)),
                                        new File(Utils.getRequiredOption(Options.GRPC_SSL_CLIENT_KEY_LOCATION))))
                        .trustManager(new File(Utils.getRequiredOption(Options.GRPC_SSL_CLIENT_TRUSTSTORE_LOCATION)))
                        .build()).useTransportSecurity();
            } else {
                return channelBuilder.usePlaintext();
            }
        } catch (SSLException sslException) {
            log.error("Exception occurred while creating SSL context for gRPC", sslException);
            throw new JaffaRpcSystemException(sslException);
        }
    }

    @Override
    public void run() {
        try {
            NettyServerBuilder serverBuilder = NettyServerBuilder.forPort(Utils.getServicePort());
            serverBuilder = addSecurityContext(serverBuilder);
            server = serverBuilder.executor(requestService).addService(new CommandServiceImpl()).build();
            server.start();
            server.awaitTermination();
        } catch (InterruptedException | IOException zmqStartupException) {
            log.error("Error during gRPC request receiver startup:", zmqStartupException);
            throw new JaffaRpcSystemException(zmqStartupException);
        }
        log.info("{} terminated", this.getClass().getSimpleName());
    }

    @Override
    public void close() {
        server.shutdown();
        asyncService.shutdown();
        requestService.shutdown();
    }

    private static class CommandServiceImpl extends CommandServiceGrpc.CommandServiceImplBase {

        private ManagedChannel getManagedChannel(Pair<String, Integer> hostAndPort) {
            return cache.computeIfAbsent(hostAndPort, key -> {
                NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(key.getLeft(), key.getRight());
                channelBuilder = GrpcAsyncAndSyncRequestReceiver.addSecurityContext(channelBuilder);
                return channelBuilder.build();
            });
        }

        @Override
        public void execute(CommandRequest request, StreamObserver<CommandResponse> responseObserver) {
            try {
                final Command command = MessageConverterHelper.fromGRPCCommandRequest(request);
                if (StringUtils.isNotBlank(command.getCallbackKey()) && StringUtils.isNotBlank(command.getCallbackClass())) {
                    Runnable runnable = () -> {
                        try {
                            Object result = RequestInvoker.invoke(command);
                            CallbackRequest callbackResponse = MessageConverterHelper.toGRPCCallbackRequest(RequestInvoker.constructCallbackContainer(command, result));
                            Pair<String, Integer> hostAndPort = Utils.getHostAndPort(command.getCallBackHost(), ":");
                            ManagedChannel channel = getManagedChannel(hostAndPort);
                            CallbackServiceGrpc.CallbackServiceBlockingStub stub = CallbackServiceGrpc.newBlockingStub(channel);
                            CallbackResponse response = stub.execute(callbackResponse);
                            if (!response.getResponse().equals("OK"))
                                throw new JaffaRpcExecutionException("Wrong value returned after async callback processing!");
                        } catch (ClassNotFoundException | NoSuchMethodException e) {
                            log.error("Error while receiving async request", e);
                            throw new JaffaRpcExecutionException(e);
                        }
                    };
                    asyncService.execute(runnable);
                    responseObserver.onNext(CommandResponse.newBuilder().setResponse(ByteString.EMPTY).build());
                } else {
                    Object result = RequestInvoker.invoke(command);
                    CommandResponse commandResponse = MessageConverterHelper.toGRPCCommandResponse(RequestInvoker.getResult(result));
                    responseObserver.onNext(commandResponse);
                }
                responseObserver.onCompleted();
            } catch (ClassNotFoundException exception) {
                log.error("Error while receiving request ", exception);
                throw new JaffaRpcSystemException(exception);
            }
        }
    }
}