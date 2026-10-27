package ru.printlab.telegram;

import java.util.UUID;

public record BotDialog(String kind, String step, UUID orderId, String payload) {
}
