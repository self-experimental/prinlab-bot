package ru.printlab.storage;

import java.util.UUID;

public record ModelFile(UUID id, UUID customerId, String telegramFileId, String name,
                        String sha256, String storageName, long size) {
}
