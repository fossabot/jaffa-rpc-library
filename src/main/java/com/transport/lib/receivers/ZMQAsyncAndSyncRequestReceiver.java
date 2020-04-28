package com.transport.lib.receivers;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.transport.lib.entities.Command;
import com.transport.lib.entities.RequestContext;
import com.transport.lib.exception.TransportExecutionException;
import com.transport.lib.exception.TransportSystemException;
import com.transport.lib.zookeeper.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;
import zmq.ZError;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.transport.lib.TransportService.*;

/*
    Class responsible for receiving synchronous and asynchronous requests using ZeroMQ
 */
public class ZMQAsyncAndSyncRequestReceiver implements Runnable, Closeable {

    private static final Logger logger = LoggerFactory.getLogger(ZMQAsyncAndSyncRequestReceiver.class);

    // ZeroMQ async requests are processed by 3 receiver threads
    private static final ExecutorService service = Executors.newFixedThreadPool(3);

    private ZMQ.Context context;
    private ZMQ.Socket socket;

    @Override
    public void run() {

        try {
            context = ZMQ.context(1);
            socket = context.socket(ZMQ.REP);
            socket.bind("tcp://" + Utils.getZeroMQBindAddress());
        } catch (
                UnknownHostException zmqStartupException) {
            logger.error("Error during callback receiver startup:", zmqStartupException);
            throw new TransportSystemException(zmqStartupException);
        }

        // New Kryo instance per thread
        Kryo kryo = new Kryo();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Receiver raw bytes
                byte[] bytes = socket.recv();
                // Unmarshal message to Command object
                Input input = new Input(new ByteArrayInputStream(bytes));
                final Command command = kryo.readObject(input, Command.class);
                // If it is async request - answer with "OK" message before target method invocation
                if (command.getCallbackKey() != null && command.getCallbackClass() != null) {
                    socket.send("OK");
                }
                // If it is async request - start target method invocation in separate thread
                if (command.getCallbackKey() != null && command.getCallbackClass() != null) {
                    Runnable runnable = () -> {
                        try {
                            // Target method will be executed in current Thread, so set service metadata
                            // like client's module.id and SecurityTicket token in ThreadLocal variables
                            RequestContext.setSourceModuleId(command.getSourceModuleId());
                            RequestContext.setSecurityTicket(command.getTicket());
                            // Invoke target method and receive result
                            Object result = invoke(command);
                            // Marshall result as CallbackContainer
                            ByteArrayOutputStream bOutput = new ByteArrayOutputStream();
                            Output output = new Output(bOutput);
                            // Construct CallbackContainer and marshall it to output stream
                            kryo.writeObject(output, constructCallbackContainer(command, result));
                            output.close();
                            // Connect to client
                            ZMQ.Context contextAsync = ZMQ.context(1);
                            ZMQ.Socket socketAsync = contextAsync.socket(ZMQ.REQ);
                            socketAsync.connect("tcp://" + command.getCallBackZMQ());
                            // And send response
                            socketAsync.send(bOutput.toByteArray());
                            Utils.closeSocketAndContext(socketAsync, contextAsync);
                        } catch (ClassNotFoundException | NoSuchMethodException e) {
                            logger.error("Error while receiving async request");
                            throw new TransportExecutionException(e);
                        }
                    };
                    service.execute(runnable);
                } else {
                    // Target method will be executed in current Thread, so set service metadata
                    // like client's module.id and SecurityTicket token in ThreadLocal variables
                    RequestContext.setSourceModuleId(command.getSourceModuleId());
                    RequestContext.setSecurityTicket(command.getTicket());
                    // Invoke target method and receive result
                    Object result = invoke(command);
                    ByteArrayOutputStream bOutput = new ByteArrayOutputStream();
                    Output output = new Output(bOutput);
                    // Marshall result
                    kryo.writeClassAndObject(output, getResult(result));
                    output.close();
                    // Send result back to client
                    socket.send(bOutput.toByteArray());
                }
            } catch (ZMQException | ZError.IOException recvTerminationException) {
                if(!recvTerminationException.getMessage().contains("156384765")){
                    logger.error("General ZMQ exception", recvTerminationException);
                    throw new TransportSystemException(recvTerminationException);
                }
            }
        }
        logger.info("{} terminated", this.getClass().getSimpleName());
    }

    @Override
    public void close() {
        Utils.closeSocketAndContext(socket, context);
        service.shutdownNow();
    }
}