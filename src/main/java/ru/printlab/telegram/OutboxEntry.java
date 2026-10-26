package ru.printlab.telegram;

public record OutboxEntry(long id, String method, String payload, int attempts) {
}
