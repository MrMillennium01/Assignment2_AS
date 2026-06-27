package com.example.chat;

import io.grpc.stub.ServerCallStreamObserver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

final class ChatStore {
    private final Map<String, ChatMessage> messagesById = new LinkedHashMap<>();
    private final List<ServerCallStreamObserver<ChatMessage>> subscribers = new CopyOnWriteArrayList<>();

    synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messagesById.values());
    }

    synchronized ChatMessage addMessage(String sender, String text) {
        ChatMessage message = ChatMessage.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setSender(sender)
                .setText(text)
                .setTimestampMillis(Instant.now().toEpochMilli())
                .setDeleted(false)
                .build();
        messagesById.put(message.getId(), message);
        return message;
    }

    synchronized ChatMessage deleteMessage(String messageId, String deletedBy) {
        ChatMessage existing = messagesById.get(messageId);
        if (existing == null) {
            return null;
        }

        ChatMessage updated = existing.toBuilder()
                .setDeleted(true)
                .setDeletedBy(deletedBy)
                .build();
        messagesById.put(messageId, updated);
        return updated;
    }

    void addSubscriber(ServerCallStreamObserver<ChatMessage> observer) {
        subscribers.add(observer);
        observer.setOnCancelHandler(() -> subscribers.remove(observer));
        observer.setOnCloseHandler(() -> subscribers.remove(observer));
    }

    void broadcast(ChatMessage message) {
        for (ServerCallStreamObserver<ChatMessage> observer : subscribers) {
            if (observer.isCancelled()) {
                subscribers.remove(observer);
                continue;
            }

            try {
                observer.onNext(message);
            } catch (RuntimeException ignored) {
                subscribers.remove(observer);
            }
        }
    }
}