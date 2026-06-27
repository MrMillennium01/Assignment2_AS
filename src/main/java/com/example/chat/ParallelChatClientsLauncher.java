package com.example.chat;

import java.util.ArrayList;
import java.util.List;

public final class ParallelChatClientsLauncher {
    private static final String HOST = "localhost";
    private static final int PORT = 50051;

    private static final int NORMAL_CLIENT_COUNT = 3;
    private static final int ADMIN_CLIENT_COUNT = 1;
    private static final long CLIENT_KEEP_ALIVE_MILLIS = 5000L;

    private ParallelChatClientsLauncher() {
    }

    public static void main(String[] args) throws InterruptedException {
        List<Thread> clientThreads = new ArrayList<>();

        for (int index = 1; index <= NORMAL_CLIENT_COUNT; index++) {
            String username = "user" + index;
            ChatClient client = new ChatClient(
                    HOST,
                    PORT,
                    username,
                    ClientRole.NORMAL,
                    List.of("Hello from " + username),
                    true,
                    CLIENT_KEEP_ALIVE_MILLIS);
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
                    List.of(),
                    true,
                    CLIENT_KEEP_ALIVE_MILLIS);
            Thread thread = new Thread(client, username);
            clientThreads.add(thread);
            thread.start();
        }

        for (Thread thread : clientThreads) {
            thread.join();
        }
    }
}