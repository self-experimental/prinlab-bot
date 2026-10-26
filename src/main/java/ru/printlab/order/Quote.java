package ru.printlab.order;

import ru.printlab.pricing.PricingSnapshot;

import java.time.Instant;
import java.util.UUID;

public record Quote(UUID id, UUID orderId, Instant expiresAt, PricingSnapshot snapshot,
                    UUID approvedBy, String approvalReason) {
}
