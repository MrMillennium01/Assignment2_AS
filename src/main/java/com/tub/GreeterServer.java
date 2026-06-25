package com.tub;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.*;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

// angelehnt an: https://github.com/grpc/grpc-java/blob/master/examples/src/main/java/io/grpc/examples/helloworld/HelloWorldServer.java
public class GreeterServer {

    static final Logger logger = Logger.getLogger(GreeterServer.class.getName());
    static final int PORT = 8980;
    static Server server;

    static void main() {
        try {
            server = Grpc.newServerBuilderForPort(PORT, InsecureServerCredentials.create())
                    .addService(new GreeterImpl())
                    .build()
                    .start();
            server.awaitTermination();
        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, e.getMessage());
        }
    }
}

class GreeterImpl extends com.tub.GreetingServiceGrpc.GreetingServiceImplBase {
    @Override
    public void greet(com.tub.GreetingRequest request, StreamObserver<com.tub.GreetingResponse> responseObserver) {
        String name = request.getName();
        String greeting = String.format("Hello, %s!", name);
        GreetingResponse resp = GreetingResponse
                .newBuilder()
                .setGreeting(greeting)
                .build();
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }
}