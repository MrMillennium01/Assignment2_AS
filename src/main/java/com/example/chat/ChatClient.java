package com.example.chat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatClient implements Runnable {
    private static final Object CONSOLE_LOCK = new Object();

    private final String host;
    private final int port;
    private final String username;
    private final ClientRole role;
    private final List<String> scriptedActions;
    private final boolean keepAliveAfterScript;
    private final long keepAliveMillis;

    public ChatClient(String host, int port, String username, ClientRole role) {
        this(host, port, username, role, List.of(), false, 0L);
    }

    public ChatClient(String host,
                      int port,
                      String username,
                      ClientRole role,
                      List<String> scriptedActions,
                      boolean keepAliveAfterScript,
                      long keepAliveMillis) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.role = role;
        this.scriptedActions = List.copyOf(scriptedActions);
        this.keepAliveAfterScript = keepAliveAfterScript;
        this.keepAliveMillis = keepAliveMillis;
    }

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 50051;

        try (Scanner scanner = new Scanner(System.in)) {
            ClientRole role = promptRole(scanner);
            String username = prompt(scanner, "Enter your username: ");
            runInteractiveSession(host, port, username, role, scanner);
        }
    }

    @Override
    public void run() {
        runScriptedSession();
    }

    public void runInteractive(Scanner scanner) {
        runInteractiveSession(host, port, username, role, scanner);
    }

    private static void runInteractiveSession(String host,
                                              int port,
                                              String username,
                                              ClientRole role,
                                              Scanner scanner) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        ChatServiceGrpc.ChatServiceBlockingStub blockingStub = ChatServiceGrpc.newBlockingStub(channel);
        CountDownLatch streamFinished = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);

        printLine("Connected as " + role.name().toLowerCase(Locale.ROOT) + " client: " + username);

        ChatServiceGrpc.ChatServiceStub asyncStub = ChatServiceGrpc.newStub(channel);
        asyncStub.subscribe(JoinRequest.newBuilder()
                        .setClientName(username)
                        .setRole(role)
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(ChatMessage value) {
                        printMessage(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        printError("Chat stream ended: " + t.getMessage());
                        running.set(false);
                        streamFinished.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        running.set(false);
                        streamFinished.countDown();
                    }
                });

        try {
            if (role == ClientRole.NORMAL) {
                runNormalClient(scanner, blockingStub, username, running);
            } else {
                runAdminClient(scanner, blockingStub, username, running);
            }
        } finally {
            channel.shutdownNow();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
                streamFinished.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runScriptedSession() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        CountDownLatch streamFinished = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        ChatServiceGrpc.ChatServiceBlockingStub blockingStub = ChatServiceGrpc.newBlockingStub(channel);
        ChatServiceGrpc.ChatServiceStub asyncStub = ChatServiceGrpc.newStub(channel);

        printLine("Connected as " + role.name().toLowerCase(Locale.ROOT) + " client: " + username);

        asyncStub.subscribe(JoinRequest.newBuilder()
                        .setClientName(username)
                        .setRole(role)
                        .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(ChatMessage value) {
                        printMessage(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        printError("Chat stream ended: " + t.getMessage());
                        running.set(false);
                        streamFinished.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        running.set(false);
                        streamFinished.countDown();
                    }
                });

        try {
            if (role == ClientRole.NORMAL) {
                for (String action : scriptedActions) {
                    if (!running.get()) {
                        break;
                    }

                    if (action.isBlank()) {
                        continue;
                    }

                    ChatMessage sent = blockingStub.sendMessage(SendMessageRequest.newBuilder()
                            .setSender(username)
                            .setRole(ClientRole.NORMAL)
                            .setText(action)
                            .build());
                    printLine("Sent message id " + sent.getId());
                }
            } else {
                for (String action : scriptedActions) {
                    if (!running.get()) {
                        break;
                    }

                    String trimmed = action.trim();
                    if (!trimmed.startsWith("/delete ")) {
                        continue;
                    }

                    String messageId = trimmed.substring("/delete ".length()).trim();
                    if (messageId.isEmpty()) {
                        continue;
                    }

                    ChatMessage updated = blockingStub.deleteMessage(DeleteMessageRequest.newBuilder()
                            .setMessageId(messageId)
                            .setDeletedBy(username)
                            .setRole(ClientRole.ADMIN)
                            .build());
                    printLine("Deleted message id " + updated.getId());
                }
            }

            if (keepAliveAfterScript) {
                waitBeforeShutdown(running);
            }
        } catch (StatusRuntimeException ex) {
            printError("Client failed: " + ex.getStatus().getDescription());
        } finally {
            channel.shutdownNow();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
                streamFinished.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void waitBeforeShutdown(AtomicBoolean running) {
        long remaining = keepAliveMillis;
        while (running.get() && remaining > 0) {
            long sleepFor = Math.min(remaining, 250L);
            try {
                Thread.sleep(sleepFor);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= sleepFor;
        }
    }

    private static ClientRole promptRole(Scanner scanner) {
        while (true) {
            String value = prompt(scanner, "Join as normal or admin? ");
            try {
                return parseRole(value);
            } catch (IllegalArgumentException ex) {
                printError(ex.getMessage());
            }
        }
    }

    private static String prompt(Scanner scanner, String message) {
        print(message);
        return scanner.nextLine().trim();
    }

    private static void runNormalClient(Scanner scanner,
                                        ChatServiceGrpc.ChatServiceBlockingStub blockingStub,
                                        String username,
                                        AtomicBoolean running) {
        printLine("Type a message and press Enter. Use /quit to leave.");

        while (running.get()) {
            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine();
            if ("/quit".equalsIgnoreCase(line.trim())) {
                running.set(false);
                break;
            }

            if (line.isBlank()) {
                continue;
            }

            try {
                ChatMessage sent = blockingStub.sendMessage(SendMessageRequest.newBuilder()
                        .setSender(username)
                        .setRole(ClientRole.NORMAL)
                        .setText(line)
                        .build());
                printLine("Sent message id " + sent.getId());
            } catch (StatusRuntimeException ex) {
                printError("Send failed: " + ex.getStatus().getDescription());
            }
        }
    }

    private static void runAdminClient(Scanner scanner,
                                        ChatServiceGrpc.ChatServiceBlockingStub blockingStub,
                                        String username,
                                        AtomicBoolean running) {
        printLine("Use /delete <messageId> to remove a message, or /quit to leave.");

        while (running.get()) {
            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine();
            String trimmed = line.trim();

            if ("/quit".equalsIgnoreCase(trimmed)) {
                running.set(false);
                break;
            }

            if (!trimmed.startsWith("/delete ")) {
                printLine("Admin clients can only delete messages.");
                continue;
            }

            String messageId = trimmed.substring("/delete ".length()).trim();
            if (messageId.isEmpty()) {
                printLine("Usage: /delete <messageId>");
                continue;
            }

            try {
                ChatMessage updated = blockingStub.deleteMessage(DeleteMessageRequest.newBuilder()
                        .setMessageId(messageId)
                        .setDeletedBy(username)
                        .setRole(ClientRole.ADMIN)
                        .build());
                printLine("Deleted message id " + updated.getId());
            } catch (StatusRuntimeException ex) {
                printError("Delete failed: " + ex.getStatus().getDescription());
            }
        }
    }

    private static ClientRole parseRole(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "admin" -> ClientRole.ADMIN;
            case "normal" -> ClientRole.NORMAL;
            default -> throw new IllegalArgumentException("Unknown role: " + value + " (use normal or admin)");
        };
    }

    private static void printMessage(ChatMessage message) {
        StringBuilder output = new StringBuilder();
        output.append('[').append(message.getId()).append("] ")
                .append(message.getSender()).append(": ")
                .append(message.getText());

        if (message.getDeleted()) {
            output.append(" [deleted by ").append(message.getDeletedBy()).append(']');
        }

        printLine(output.toString());
    }

    private static void printLine(String message) {
        synchronized (CONSOLE_LOCK) {
            System.out.println(message);
        }
    }

    private static void print(String message) {
        synchronized (CONSOLE_LOCK) {
            System.out.print(message);
            System.out.flush();
        }
    }

    private static void printError(String message) {
        synchronized (CONSOLE_LOCK) {
            System.err.println(message);
        }
    }
}