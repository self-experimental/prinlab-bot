package ru.printlab.slicing;

import java.util.UUID;

public record SliceJob(UUID id, UUID orderId, int attempts) {
}
