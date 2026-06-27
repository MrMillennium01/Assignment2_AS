package com.example.chat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.io.IOException;

public final class ChatServer {
    private ChatServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 50051;
        Server server = ServerBuilder.forPort(port)
                .addService(new ChatServiceImpl())
                .build()
                .start();

        System.out.println("Chat server started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }

    private static final class ChatServiceImpl extends ChatServiceGrpc.ChatServiceImplBase {
        private final ChatStore store = new ChatStore();

        @Override
        public void subscribe(JoinRequest request, StreamObserver<ChatMessage> responseObserver) {
            if (request.getRole() == ClientRole.CLIENT_ROLE_UNSPECIFIED) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("client role is required")
                        .asRuntimeException());
                return;
            }

            if (!(responseObserver instanceof ServerCallStreamObserver)) {
                responseObserver.onError(Status.INTERNAL
                        .withDescription("stream observer is not supported")
                        .asRuntimeException());
                return;
            }

            ServerCallStreamObserver<ChatMessage> serverObserver = (ServerCallStreamObserver<ChatMessage>) responseObserver;
            store.addSubscriber(serverObserver);

            for (ChatMessage message : store.snapshot()) {
                serverObserver.onNext(message);
            }
        }

        @Override
        public void sendMessage(SendMessageRequest request, StreamObserver<ChatMessage> responseObserver) {
            if (request.getRole() != ClientRole.NORMAL) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("only normal clients can send messages")
                        .asRuntimeException());
                return;
            }

            if (request.getText().isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("message text must not be empty")
                        .asRuntimeException());
                return;
            }

            ChatMessage message = store.addMessage(request.getSender(), request.getText());
            store.broadcast(message);
            responseObserver.onNext(message);
            responseObserver.onCompleted();
        }

        @Override
        public void deleteMessage(DeleteMessageRequest request, StreamObserver<ChatMessage> responseObserver) {
            if (request.getRole() != ClientRole.ADMIN) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("only admin clients can delete messages")
                        .asRuntimeException());
                return;
            }

            if (request.getMessageId().isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("message id must not be empty")
                        .asRuntimeException());
                return;
            }

            ChatMessage updated = store.deleteMessage(request.getMessageId(), request.getDeletedBy());
            if (updated == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("message not found")
                        .asRuntimeException());
                return;
            }

            store.broadcast(updated);
            responseObserver.onNext(updated);
            responseObserver.onCompleted();
        }
    }
}