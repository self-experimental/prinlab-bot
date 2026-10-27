package ru.printlab.telegram;

import tools.jackson.databind.JsonNode;

public interface TelegramGateway {
    JsonNode updates(long offset);
    byte[] download(String fileId);
    JsonNode deliver(String method, String payload);
}
