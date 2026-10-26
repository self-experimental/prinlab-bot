package ru.printlab.pricing;

import java.math.BigDecimal;

public record CostBreakdown(BigDecimal material, BigDecimal electricity, BigDecimal depreciation,
                            BigDecimal maintenance, BigDecimal setupLabor, BigDecimal finishingLabor,
                            BigDecimal failureReserve, BigDecimal packaging, BigDecimal acquisition,
                            BigDecimal delivery, BigDecimal fixedPaymentFee, BigDecimal total) {
}
