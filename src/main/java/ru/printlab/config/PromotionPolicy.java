package ru.printlab.config;

import java.math.BigDecimal;
import java.time.Instant;

public record PromotionPolicy(boolean enabled, String code, BigDecimal rate, BigDecimal maxDiscount,
                              int maxPaidOrders, BigDecimal budget, Instant endsAt) {
}
