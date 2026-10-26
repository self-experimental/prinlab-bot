package ru.printlab.order;

import ru.printlab.pricing.CostBreakdown;
import ru.printlab.pricing.Estimate;

import java.math.BigDecimal;

public record ActualCosts(Estimate consumption, CostBreakdown costsAtSavedRates, BigDecimal extraCosts,
                          BigDecimal totalCost, BigDecimal contribution) {
}
