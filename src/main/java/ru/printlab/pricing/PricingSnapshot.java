package ru.printlab.pricing;

import ru.printlab.config.CostRates;
import ru.printlab.config.Material;
import ru.printlab.config.PromotionPolicy;
import ru.printlab.catalog.FilamentColor;

public record PricingSnapshot(String tariffVersion, Material material, CostRates rates,
                              PromotionPolicy promotion, Estimate estimate, boolean newCustomer,
                              CostBreakdown costs, PublicPrice publicPrice, Economics economics, FilamentColor color) {
    public PricingSnapshot(String tariffVersion, Material material, CostRates rates,
                           PromotionPolicy promotion, Estimate estimate, boolean newCustomer,
                           CostBreakdown costs, PublicPrice publicPrice, Economics economics) {
        this(tariffVersion, material, rates, promotion, estimate, newCustomer, costs, publicPrice, economics, null);
    }

    public PricingSnapshot withColor(FilamentColor selected) {
        return new PricingSnapshot(tariffVersion, material, rates, promotion, estimate, newCustomer,
                costs, publicPrice, economics, selected);
    }
}
