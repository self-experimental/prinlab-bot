package ru.printlab.pricing;

import org.springframework.stereotype.Service;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Money;
import ru.printlab.config.AppProperties;
import ru.printlab.config.CostRates;
import ru.printlab.config.Material;
import ru.printlab.config.PromotionPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PricingService {
    private final AppProperties properties;

    public PricingService(AppProperties properties) {
        this.properties = properties;
    }

    public PricingSnapshot calculate(String materialId, Estimate estimate, boolean newCustomer,
                                     boolean applyPromo, String promotionNote) {
        return calculate(materialId, estimate, newCustomer, applyPromo, promotionNote, properties.promotion());
    }

    public PricingSnapshot calculate(String materialId, Estimate estimate, boolean newCustomer,
                                     boolean applyPromo, String promotionNote, PromotionPolicy policy) {
        return calculate(properties.materials().get(materialId), estimate, newCustomer, applyPromo, promotionNote, policy);
    }

    public PricingSnapshot calculate(Material material, Estimate estimate, boolean newCustomer,
                                     boolean applyPromo, String promotionNote, PromotionPolicy policy) {
        if (!properties.demoMode() && !properties.pricingConfirmed()) {
            throw new BusinessException("Сначала настройте и подтвердите тарифы: PRICING_CONFIRMED=true.");
        }
        if (material == null || !material.available()) {
            throw new BusinessException("Материал недоступен. Согласуйте замену.");
        }
        estimate.validate();
        CostRates rates = properties.rates();
        CostBreakdown costs = costs(material, rates, estimate, newCustomer, true);
        int chargedLaunches = estimate.plateCount();
        if (rates.launchFeePerOrder()) {
            chargedLaunches = 1;
        }
        BigDecimal launchFee = Money.rub(rates.launchFee().multiply(BigDecimal.valueOf(chargedLaunches)));
        BigDecimal ordinary = ordinaryPrice(material, costs, rates, estimate, launchFee);
        BigDecimal discount = Money.ZERO;
        if (applyPromo) {
            BigDecimal discountBase = ordinary;
            if (rates.discountLaunchFee()) {
                discountBase = discountBase.add(launchFee);
            }
            discount = Money.rub(discountBase.multiply(policy.rate())).min(discountBase);
            if (policy.maxDiscount() != null) {
                discount = discount.min(policy.maxDiscount());
            }
        }
        BigDecimal total = Money.rub(ordinary.add(launchFee).subtract(discount).add(estimate.deliveryPrice()));
        PublicPrice price = new PublicPrice(ordinary, discount, Money.rub(estimate.deliveryPrice()),
                total, "RUB", promotionNote, launchFee);
        Economics economics = economics(costs, total, rates, launchFee);
        return new PricingSnapshot(rates.version(), material, rates, policy, estimate,
                newCustomer, costs, price, economics);
    }

    private BigDecimal ordinaryPrice(Material material, CostBreakdown costs, CostRates rates, Estimate estimate, BigDecimal launchFee) {
        if (rates.materialSaleMultiplier() != null) {
            return Money.rub(estimate.grams().add(estimate.extraWasteGrams()).multiply(material.costPerKg())
                    .multiply(rates.materialSaleMultiplier()).divide(new BigDecimal("1000")));
        }
        BigDecimal ordinary;
        if (rates.targetProfitMarkup() == null) {
            // Historical tariffs used a share of revenue, not a return on all costs.
            BigDecimal printCost = costs.total().subtract(costs.delivery());
            BigDecimal divisor = BigDecimal.ONE.subtract(rates.paymentRate()).subtract(rates.taxRate())
                    .subtract(rates.targetMargin());
            ordinary = printCost.divide(divisor, 12, RoundingMode.HALF_UP);
        } else {
            BigDecimal receiptRate = rates.paymentRate().add(rates.taxRate());
            BigDecimal multiplier = BigDecimal.ONE.add(rates.targetProfitMarkup());
            BigDecimal divisor = BigDecimal.ONE.subtract(receiptRate.multiply(multiplier));
            BigDecimal targetTotal = costs.total()
                    .multiply(multiplier)
                    .add(launchFee)
                    .divide(divisor, 12, RoundingMode.CEILING);
            BigDecimal minimumTotal = costs.total()
                    .add(launchFee)
                    .add(rates.minimumContribution())
                    .divide(BigDecimal.ONE.subtract(receiptRate), 12, RoundingMode.CEILING);
            // The target is printing profit; the startup charge is earned on top of it.
            ordinary = targetTotal.max(minimumTotal).subtract(launchFee).subtract(estimate.deliveryPrice());
        }
        return ordinary.max(rates.minimumPrintPrice()).max(Money.ZERO)
                .divide(BigDecimal.TEN, 0, RoundingMode.CEILING)
                .multiply(BigDecimal.TEN)
                .setScale(2);
    }

    public PricingSnapshot withDelivery(PricingSnapshot original, BigDecimal deliveryPrice) {
        Money.nonNegative(deliveryPrice, "Доставка");
        // CDEK receives its fee directly. Keep the carrier quote outside our cost and receipt.
        PublicPrice previous = original.publicPrice();
        return replacePrice(original, previous.regularPrintPrice(), previous.discount(), previous.promotionNote());
    }

    public PricingSnapshot withPrintPrice(PricingSnapshot original, BigDecimal amount, boolean applyPromo, String note) {
        Money.nonNegative(amount, "Цена печати");
        BigDecimal discount = Money.ZERO;
        if (applyPromo) {
            discount = Money.rub(amount.multiply(original.promotion().rate())).min(amount);
            if (original.promotion().maxDiscount() != null) discount = discount.min(original.promotion().maxDiscount());
        }
        return replacePrice(original, Money.rub(amount), discount, note);
    }

    private PricingSnapshot replacePrice(PricingSnapshot original, BigDecimal ordinary, BigDecimal discount, String note) {
        Estimate source = original.estimate();
        Estimate estimate = new Estimate(source.grams(), source.extraWasteGrams(), source.printMinutes(),
                source.setupMinutes(), source.finishingMinutes(), BigDecimal.ZERO, BigDecimal.ZERO, source.plateCount());
        CostBreakdown c = original.costs();
        CostBreakdown costs = new CostBreakdown(c.material(), c.electricity(), c.depreciation(), c.maintenance(),
                c.setupLabor(), c.finishingLabor(), c.failureReserve(), c.packaging(), c.acquisition(),
                Money.ZERO, c.fixedPaymentFee(), Money.rub(c.total().subtract(c.delivery())));
        BigDecimal total = Money.rub(ordinary.subtract(discount));
        PublicPrice price = new PublicPrice(ordinary, discount, Money.ZERO, total, "RUB", note, Money.ZERO);
        return new PricingSnapshot(original.tariffVersion(), original.material(), original.rates(), original.promotion(),
                estimate, original.newCustomer(), costs, price, economics(costs, total, original.rates(), Money.ZERO), original.color());
    }

    public CostBreakdown costs(Material material, CostRates rates, Estimate estimate,
                               boolean newCustomer, boolean includeFailureReserve) {
        estimate.validate();
        BigDecimal hours = Money.hours(estimate.printMinutes());
        BigDecimal materialCost = Money.rub(estimate.grams().add(estimate.extraWasteGrams())
                .multiply(material.costPerKg()).divide(new BigDecimal("1000")));
        BigDecimal electricity = Money.rub(hours.multiply(rates.printerKw()).multiply(rates.electricityPerKwh()));
        BigDecimal depreciation = Money.rub(hours.multiply(rates.effectiveDepreciationPerHour()));
        BigDecimal maintenance = Money.rub(hours.multiply(rates.maintenancePerHour()));
        BigDecimal setup = Money.rub(Money.hours(estimate.setupMinutes()).multiply(rates.laborPerHour()));
        BigDecimal finishing = Money.rub(Money.hours(estimate.finishingMinutes()).multiply(rates.laborPerHour()));
        BigDecimal repeatable = materialCost.add(electricity).add(depreciation).add(maintenance).add(setup);
        BigDecimal reserve = Money.ZERO;
        if (includeFailureReserve) {
            reserve = Money.rub(repeatable.multiply(rates.failureProbability())
                    .divide(BigDecimal.ONE.subtract(rates.failureProbability()), 12, RoundingMode.HALF_UP));
        }
        BigDecimal acquisition = Money.ZERO;
        if (newCustomer) {
            acquisition = Money.rub(rates.acquisitionPerNewCustomer());
        }
        BigDecimal total = Money.rub(repeatable.add(finishing).add(reserve).add(rates.packaging())
                .add(acquisition).add(estimate.deliveryCost()).add(rates.paymentFixed()));
        return new CostBreakdown(materialCost, electricity, depreciation, maintenance, setup, finishing,
                reserve, Money.rub(rates.packaging()), acquisition, Money.rub(estimate.deliveryCost()),
                Money.rub(rates.paymentFixed()), total);
    }

    public Economics economics(CostBreakdown costs, BigDecimal total, CostRates rates, BigDecimal launchFee) {
        BigDecimal paymentFee = Money.rub(total.multiply(rates.paymentRate()));
        BigDecimal tax = Money.rub(total.multiply(rates.taxRate()));
        BigDecimal totalCost = Money.rub(costs.total().add(paymentFee).add(tax));
        BigDecimal contribution = Money.rub(total.subtract(totalCost));
        BigDecimal printingContribution = Money.rub(contribution.subtract(launchFee));
        BigDecimal requiredContribution = totalCost.multiply(rates.minimumProfitMarkup())
                .setScale(2, RoundingMode.CEILING)
                .max(rates.minimumContribution())
                .add(launchFee);
        BigDecimal profitMarkup = null;
        if (totalCost.signum() > 0) {
            profitMarkup = printingContribution.divide(totalCost, 8, RoundingMode.HALF_UP);
        }
        return new Economics(paymentFee, tax, contribution, requiredContribution,
                contribution.compareTo(requiredContribution) < 0, totalCost, profitMarkup,
                rates.minimumProfitMarkup(), printingContribution);
    }
}
