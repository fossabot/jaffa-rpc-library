package com.transport.lib.common;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.transport.lib.zookeeper.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

import static com.transport.lib.common.TransportService.*;

@SuppressWarnings("WeakerAccess, unchecked")
public class ZMQSyncRequestReceiver implements Runnable {

    private static Logger logger = LoggerFactory.getLogger(ZMQSyncRequestReceiver.class);

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                byte[] bytes = socket.recv();
                Kryo kryo = new Kryo();
                Input input = new Input(new ByteArrayInputStream(bytes));
                Command command = kryo.readObject(input, Command.class);
                TransportContext.setSourceModuleId(command.getSourceModuleId());
                Object result = invoke(command);
                ByteArrayOutputStream bOutput = new ByteArrayOutputStream();
                Output output = new Output(bOutput);
                if(command.getCallbackKey() != null && command.getCallbackClass() != null){
                    socket.send("OK");
                    ZMQ.Context contextAsync = ZMQ.context(1);
                    ZMQ.Socket socketAsync = contextAsync.socket(ZMQ.REQ);
                    socketAsync.connect("tcp://" + command.getCallBackZMQ());
                    CallbackContainer callbackContainer = new CallbackContainer();
                    callbackContainer.setKey(command.getCallbackKey());
                    callbackContainer.setListener(command.getCallbackClass());
                    callbackContainer.setResult(getResult(result));
                    Method targetMethod = getTargetMethod(command);
                    if(map.containsKey(targetMethod.getReturnType())){
                        callbackContainer.setResultClass(map.get(targetMethod.getReturnType()).getName());
                    }else{
                        callbackContainer.setResultClass(targetMethod.getReturnType().getName());
                    }
                    kryo.writeObject(output, callbackContainer);
                    output.close();
                    socketAsync.send(bOutput.toByteArray());
                    Utils.closeSocketAndContext(socketAsync, contextAsync);
                }else{
                    kryo.writeClassAndObject(output, getResult(result));
                    output.close();
                    socket.send(bOutput.toByteArray());
                }
            } catch (Exception e) {
                logger.error("Error during ZMQ method invocation:", e);
            }
        }
        Utils.closeSocketAndContext(socket, context);
    }
}
