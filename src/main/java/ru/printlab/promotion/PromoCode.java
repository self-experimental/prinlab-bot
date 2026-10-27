package ru.printlab.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PromoCode(UUID id, String code, PromoType type, BigDecimal percent, Instant createdAt,
                        Instant startsAt, Instant expiresAt, boolean enabled, boolean deleted,
                        String failureReason, Integer maxUsers, int maxOrdersPerUser, int usedUsers, int usedOrders,
                        String campaignGroup, String prerequisiteCode, int prerequisitePaidUsers) {
}
