package com.example.chat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatClient implements Runnable, AutoCloseable {
    private static final Object CONSOLE_LOCK = new Object();

    private final String host;
    private final int port;
    private final String username;
    private final ClientRole role;
    private final boolean livePrintIncoming;

    private final List<ChatMessage> inbox = new CopyOnWriteArrayList<>();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ManagedChannel channel;
    private ChatServiceGrpc.ChatServiceBlockingStub blockingStub;

    public ChatClient(String host, int port, String username, ClientRole role) {
        this(host, port, username, role, true);
    }

    public ChatClient(String host, int port, String username, ClientRole role, boolean livePrintIncoming) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.role = role;
        this.livePrintIncoming = livePrintIncoming;
    }

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 50051;

        try (Scanner scanner = new Scanner(System.in)) {
            ClientRole role = promptRole(scanner);
            String username = prompt(scanner, "Enter your username: ");

            try (ChatClient client = new ChatClient(host, port, username, role, true)) {
                client.start();
                client.awaitReady();

                if (role == ClientRole.NORMAL) {
                    runNormalInteractive(scanner, client);
                } else {
                    runAdminInteractive(scanner, client);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            start();
            awaitReady();
            awaitClosed();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    public synchronized void start() {
        if (started.get()) {
            return;
        }

        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        blockingStub = ChatServiceGrpc.newBlockingStub(channel);
        ChatServiceGrpc.ChatServiceStub asyncStub = ChatServiceGrpc.newStub(channel);
        running.set(true);

        asyncStub.subscribe(JoinRequest.newBuilder()
                .setClientName(username)
                .setRole(role)
                .build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(ChatMessage value) {
                        inbox.add(value);
                        if (livePrintIncoming) {
                            printLine(formatMessage(value));
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        running.set(false);
                        printError(username + " stream ended: " + t.getMessage());
                        closedLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        running.set(false);
                        closedLatch.countDown();
                    }
                });

        started.set(true);
        readyLatch.countDown();
        printLine("Connected as " + role.name().toLowerCase(Locale.ROOT) + " client: " + username);
    }

    public void awaitReady() throws InterruptedException {
        readyLatch.await();
    }

    public void awaitClosed() throws InterruptedException {
        closedLatch.await();
    }

    public String username() {
        return username;
    }

    public ClientRole role() {
        return role;
    }

    public List<ChatMessage> inboxSnapshot() {
        return new ArrayList<>(inbox);
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized ChatMessage sendMessage(String text) {
        ensureReady();
        if (role != ClientRole.NORMAL) {
            throw new IllegalStateException("Only normal clients can send messages");
        }

        return blockingStub.sendMessage(SendMessageRequest.newBuilder()
                .setSender(username)
                .setRole(ClientRole.NORMAL)
                .setText(text)
                .build());
    }

    public synchronized ChatMessage deleteMessage(String messageId) {
        ensureReady();
        if (role != ClientRole.ADMIN) {
            throw new IllegalStateException("Only admin clients can delete messages");
        }

        return blockingStub.deleteMessage(DeleteMessageRequest.newBuilder()
                .setMessageId(messageId)
                .setDeletedBy(username)
                .setRole(ClientRole.ADMIN)
                .build());
    }

    public void printInbox() {
        List<ChatMessage> snapshot = inboxSnapshot();
        if (snapshot.isEmpty()) {
            printLine("[" + username + "] inbox is empty");
            return;
        }

        printLine("--- perspective: " + username + " (" + role.name().toLowerCase(Locale.ROOT) + ") ---");
        for (ChatMessage message : snapshot) {
            printLine("[" + username + "] " + formatMessage(message));
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (channel != null) {
            channel.shutdownNow();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        closedLatch.countDown();
    }

    private void ensureReady() {
        if (!started.get() || blockingStub == null) {
            throw new IllegalStateException("ChatClient has not been started yet");
        }
    }

    private static void runNormalInteractive(Scanner scanner, ChatClient client) {
        printLine("Type a message and press Enter. Use /quit to leave.");

        while (client.isRunning()) {
            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine();
            if ("/quit".equalsIgnoreCase(line.trim())) {
                break;
            }

            if (line.isBlank()) {
                continue;
            }

            try {
                ChatMessage sent = client.sendMessage(line);
                printLine("Sent message id " + sent.getId());
            } catch (StatusRuntimeException ex) {
                printError("Send failed: " + ex.getStatus().getDescription());
            }
        }
    }

    private static void runAdminInteractive(Scanner scanner, ChatClient client) {
        printLine("Use /delete <messageId> to remove a message, or /quit to leave.");

        while (client.isRunning()) {
            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine().trim();
            if ("/quit".equalsIgnoreCase(line)) {
                break;
            }

            if (!line.startsWith("/delete ")) {
                printLine("Admin clients can only delete messages.");
                continue;
            }

            String messageId = line.substring("/delete ".length()).trim();
            if (messageId.isEmpty()) {
                printLine("Usage: /delete <messageId>");
                continue;
            }

            try {
                ChatMessage updated = client.deleteMessage(messageId);
                printLine("Deleted message id " + updated.getId());
            } catch (StatusRuntimeException ex) {
                printError("Delete failed: " + ex.getStatus().getDescription());
            }
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

    private static ClientRole parseRole(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "admin" -> ClientRole.ADMIN;
            case "normal" -> ClientRole.NORMAL;
            default -> throw new IllegalArgumentException("Unknown role: " + value + " (use normal or admin)");
        };
    }

    private static String formatMessage(ChatMessage message) {
        StringBuilder output = new StringBuilder();
        output.append('[').append(message.getId()).append("] ")
                .append(message.getSender()).append(": ")
                .append(message.getText());

        if (message.getDeleted()) {
            output.append(" [deleted by ").append(message.getDeletedBy()).append(']');
        }

        return output.toString();
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