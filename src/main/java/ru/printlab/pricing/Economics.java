package ru.printlab.pricing;

import java.math.BigDecimal;

public record Economics(BigDecimal paymentFee, BigDecimal estimatedTax, BigDecimal contribution,
                        BigDecimal minimumContribution, boolean needsReview, BigDecimal totalCost,
                        BigDecimal profitMarkup, BigDecimal minimumProfitMarkup, BigDecimal printingContribution) {
}
