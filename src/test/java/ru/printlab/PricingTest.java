package ru.printlab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.printlab.common.BusinessException;
import ru.printlab.config.AppProperties;
import ru.printlab.config.CostRates;
import ru.printlab.pricing.Estimate;
import ru.printlab.pricing.PricingService;
import ru.printlab.pricing.PricingSnapshot;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PricingTest {
    @Autowired ru.printlab.operator.OperatorProperties operators;
    @Autowired PricingService pricing;
    @Autowired AppProperties properties;

    @Test
    void ordinaryPriceDoesNotChangeWhenPromotionIsRequestedAndLaborIsZero() {
        PricingSnapshot ordinary = pricing.calculate("pla-black", Fixtures.estimate(), true, false, "Без акции");
        PricingSnapshot discounted = pricing.calculate("pla-black", Fixtures.estimate(), true, true, "FIRST50");
        assertThat(ordinary.publicPrice().regularPrintPrice()).isEqualByComparingTo("550.00");
        assertThat(discounted.publicPrice().regularPrintPrice()).isEqualByComparingTo(ordinary.publicPrice().regularPrintPrice());
        assertThat(discounted.publicPrice().discount()).isEqualByComparingTo("275.00");
        assertThat(discounted.costs().setupLabor()).isZero();
        assertThat(discounted.costs().finishingLabor()).isZero();
        assertThat(discounted.economics().needsReview()).isTrue();
    }

    @Test
    void independentHandCalculationOfEveryCostLine() {
        PricingSnapshot quote = pricing.calculate("pla-black", Fixtures.estimate(), true, false, "");
        assertThat(quote.costs().material()).isEqualByComparingTo("90.00");
        assertThat(quote.costs().electricity()).isEqualByComparingTo("3.68");
        assertThat(quote.costs().depreciation()).isEqualByComparingTo("52.50");
        assertThat(quote.costs().maintenance()).isEqualByComparingTo("17.50");
        assertThat(quote.costs().failureReserve()).isEqualByComparingTo("8.61");
        assertThat(quote.costs().packaging()).isEqualByComparingTo("35.00");
        assertThat(quote.costs().acquisition()).isEqualByComparingTo("80.00");
        assertThat(quote.costs().total()).isEqualByComparingTo("287.29");
        assertThat(quote.economics().paymentFee()).isEqualByComparingTo("16.50");
        assertThat(quote.economics().estimatedTax()).isEqualByComparingTo("22.00");
        assertThat(quote.economics().contribution()).isEqualByComparingTo("224.21");
    }

    @Test
    void shippingIsNotDiscountedAndFeesIncludeShipping() {
        Estimate estimate = new Estimate(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("210"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("200"), new BigDecimal("300"));
        PricingSnapshot quote = pricing.calculate("pla-black", estimate, true, true, "");
        assertThat(quote.publicPrice().regularPrintPrice()).isEqualByComparingTo("550.00");
        assertThat(quote.publicPrice().total()).isEqualByComparingTo("575.00");
        assertThat(quote.economics().paymentFee()).isEqualByComparingTo("17.25");
        assertThat(quote.economics().estimatedTax()).isEqualByComparingTo("23.00");
    }

    @Test
    void largeOrderHasUncappedDiscountAndExplicitWasteIsCountedOnce() {
        Estimate large = new Estimate(new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("600"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        PricingSnapshot quote = pricing.calculate("pla-black", large, true, true, "");
        assertThat(quote.costs().material()).isEqualByComparingTo("1212.00");
        assertThat(quote.publicPrice().discount()).isGreaterThan(new BigDecimal("600.00"));
        assertThat(quote.publicPrice().discount()).isEqualByComparingTo(
                quote.publicPrice().regularPrintPrice().multiply(new BigDecimal("0.50")));
    }

    @Test
    void actualConsumptionDoesNotAddFailureReserveAgain() {
        var costs = pricing.costs(properties.materials().get("pla-black"), properties.rates(), Fixtures.estimate(), false, false);
        assertThat(costs.failureReserve()).isZero();
        assertThat(costs.acquisition()).isZero();
        assertThat(costs.total()).isEqualByComparingTo("198.68");
    }

    @Test
    void rejectsUnavailableMaterialAndInvalidEstimate() {
        assertThatThrownBy(() -> pricing.calculate("petg-black", Fixtures.estimate(), true, false, ""))
                .isInstanceOf(BusinessException.class);
        Estimate bad = new Estimate(new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> pricing.calculate("pla-black", bad, true, false, ""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unconfirmedProductionRatesCannotCalculatePrice() {
        AppProperties prod = new AppProperties(false, false, "", properties.adminIds(), properties.storageDir(), false,
                properties.rates(), properties.promotion(), properties.materials(), "", "", "", "");
        assertThatThrownBy(() -> new PricingService(prod).calculate("pla-black", Fixtures.estimate(), true, false, ""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ratesCannotHaveZeroOrNegativePriceDivisor() {
        CostRates original = properties.rates();
        CostRates invalid = new CostRates("bad", original.electricityPerKwh(), original.printerKw(),
                original.depreciationPerHour(), original.maintenancePerHour(), original.laborPerHour(),
                original.packaging(), original.acquisitionPerNewCustomer(), original.failureProbability(),
                new BigDecimal("0.6"), original.paymentFixed(), new BigDecimal("0.4"), original.targetMargin(),
                original.minimumPrintPrice(), original.minimumContribution(), original.quoteHours());
        AppProperties config = new AppProperties(true, false, "", properties.adminIds(), properties.storageDir(), false,
                invalid, properties.promotion(), properties.materials(), "", "", "", "");
        assertThatThrownBy(() -> new ru.printlab.config.RuntimeConfig(config, operators)).isInstanceOf(IllegalStateException.class);
    }
}
