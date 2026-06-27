package com.example.chat;

import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public final class ParallelChatClientsLauncher {
    private static final String HOST = "localhost";
    private static final int PORT = 50051;

    private static final int NORMAL_CLIENT_COUNT = 3;
    private static final int ADMIN_CLIENT_COUNT = 1;

    private ParallelChatClientsLauncher() {
    }

    public static void main(String[] args) throws InterruptedException {
        Map<String, ChatClient> sessions = new LinkedHashMap<>();
        List<Thread> clientThreads = new ArrayList<>();

        for (int index = 1; index <= NORMAL_CLIENT_COUNT; index++) {
            String username = "user" + index;
            ChatClient client = new ChatClient(
                    HOST,
                    PORT,
                    username,
                    ClientRole.NORMAL,
                    false);
            sessions.put(username, client);
            Thread thread = new Thread(client, username);
            clientThreads.add(thread);
            thread.start();
        }

        for (int index = 1; index <= ADMIN_CLIENT_COUNT; index++) {
            String username = "admin" + index;
            ChatClient client = new ChatClient(
                    HOST,
                    PORT,
                    username,
                    ClientRole.ADMIN,
                    false);
            sessions.put(username, client);
            Thread thread = new Thread(client, username);
            clientThreads.add(thread);
            thread.start();
        }

        for (ChatClient session : sessions.values()) {
            session.awaitReady();
        }

        String activeName = sessions.keySet().iterator().next();

        try (Scanner scanner = new Scanner(System.in)) {
            printHelp();

            while (true) {
                ChatClient active = sessions.get(activeName);
                printPrompt(active);

                if (!scanner.hasNextLine()) {
                    break;
                }

                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }

                if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                    break;
                }

                if ("help".equalsIgnoreCase(line)) {
                    printHelp();
                    continue;
                }

                if ("list".equalsIgnoreCase(line)) {
                    printSessions(sessions, activeName);
                    continue;
                }

                if ("show".equalsIgnoreCase(line)) {
                    active.printInbox();
                    continue;
                }

                if (line.startsWith("use ")) {
                    String requested = line.substring(4).trim();
                    if (!sessions.containsKey(requested)) {
                        System.out.println("Unknown client: " + requested);
                        continue;
                    }

                    activeName = requested;
                    System.out.println("Switched to " + activeName);
                    sessions.get(activeName).printInbox();
                    continue;
                }

                if (line.startsWith("send ")) {
                    if (active.role() != ClientRole.NORMAL) {
                        System.out.println("The active client is not a normal client, so it cannot send messages.");
                        continue;
                    }

                    String text = line.substring(5).trim();
                    if (text.isEmpty()) {
                        System.out.println("Usage: send <message>");
                        continue;
                    }

                    try {
                        ChatMessage sent = active.sendMessage(text);
                        System.out.println("Sent as " + activeName + ": " + sent.getId());
                    } catch (StatusRuntimeException ex) {
                        System.out.println("Send failed: " + ex.getStatus().getDescription());
                    }
                    continue;
                }

                if (line.startsWith("delete ")) {
                    if (active.role() != ClientRole.ADMIN) {
                        System.out.println("The active client is not admin, so it cannot delete messages.");
                        continue;
                    }

                    String messageId = line.substring(7).trim();
                    if (messageId.isEmpty()) {
                        System.out.println("Usage: delete <messageId>");
                        continue;
                    }

                    try {
                        ChatMessage updated = active.deleteMessage(messageId);
                        System.out.println("Deleted as " + activeName + ": " + updated.getId());
                    } catch (StatusRuntimeException ex) {
                        System.out.println("Delete failed: " + ex.getStatus().getDescription());
                    }
                    continue;
                }

                System.out.println("Unknown command. Type help.");
            }
        } finally {
            for (ChatClient session : sessions.values()) {
                session.close();
            }

            for (Thread thread : clientThreads) {
                thread.join();
            }
        }
    }

    private static void printHelp() {
        System.out.println("Commands: list, use <client>, show, send <text>, delete <messageId>, help, quit");
    }

    private static void printSessions(Map<String, ChatClient> sessions, String activeName) {
        for (Map.Entry<String, ChatClient> entry : sessions.entrySet()) {
            String marker = entry.getKey().equals(activeName) ? "*" : " ";
            System.out.println(marker + " " + entry.getKey() + " (" + entry.getValue().role().name().toLowerCase() + ")");
        }
    }

    private static void printPrompt(ChatClient active) {
        System.out.print("[" + active.username() + "/" + active.role().name().toLowerCase() + "]> ");
        System.out.flush();
    }
    }
}