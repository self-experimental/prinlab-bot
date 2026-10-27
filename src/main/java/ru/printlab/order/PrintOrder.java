package ru.printlab.order;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public record PrintOrder(UUID id, long number, UUID customerId, OrderStatus status, UUID modelId,
                         String materialId, String profile, int infill, int quantity, String promoCode,
                         UUID quoteId, Instant createdAt, Instant paidAt, UUID colorId, BigDecimal layerHeightMm) {
    public PrintOrder(UUID id, long number, UUID customerId, OrderStatus status, UUID modelId,
                      String materialId, String profile, int infill, int quantity, String promoCode,
                      UUID quoteId, Instant createdAt, Instant paidAt) {
        this(id, number, customerId, status, modelId, materialId, profile, infill, quantity, promoCode,
                quoteId, createdAt, paidAt, null, defaultLayer(profile));
    }

    private static BigDecimal defaultLayer(String profile) {
        if ("FINE".equals(profile)) {
            return new BigDecimal("0.12");
        }
        return new BigDecimal("0.20");
    }
}
