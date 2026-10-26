package ru.printlab.pricing;

import ru.printlab.config.CostRates;
import ru.printlab.config.Material;
import ru.printlab.config.PromotionPolicy;

public record PricingSnapshot(String tariffVersion, Material material, CostRates rates,
                              PromotionPolicy promotion, Estimate estimate, boolean newCustomer,
                              CostBreakdown costs, PublicPrice publicPrice, Economics economics) {
}
