package ru.printlab.telegram;

import java.util.UUID;

public record OutboxEntry(long id, String method, String payload, int attempts, UUID orderId) {
}
