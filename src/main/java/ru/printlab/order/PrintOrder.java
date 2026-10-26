package ru.printlab.order;

import java.time.Instant;
import java.util.UUID;

public record PrintOrder(UUID id, long number, UUID customerId, OrderStatus status, UUID modelId,
                         String materialId, String profile, int infill, int quantity, String promoCode,
                         UUID quoteId, Instant createdAt, Instant paidAt) {
}
