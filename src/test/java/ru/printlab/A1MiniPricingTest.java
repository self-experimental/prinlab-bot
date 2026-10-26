package ru.printlab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import ru.printlab.common.Json;
import ru.printlab.config.AppProperties;
import ru.printlab.config.CostRates;
import ru.printlab.pricing.Estimate;
import ru.printlab.pricing.PricingService;
import ru.printlab.pricing.PublicPrice;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "printlab.rates.version=a1-mini-v3",
        "printlab.rates.target-profit-markup=1.00",
        "printlab.rates.minimum-profit-markup=0.50",
        "printlab.rates.minimum-contribution=0.00",
        "printlab.rates.electricity-per-kwh=13.00",
        "printlab.materials.pla-black.cost-per-kg=1000.00",
        "printlab.rates.printer-purchase-cost=20000.00",
        "printlab.rates.printer-lifetime-hours=2000",
        "printlab.rates.launch-fee=150.00",
        "printlab.rates.launch-fee-per-order=true",
        "printlab.rates.minimum-print-price=0.00",
        "printlab.rates.failure-probability=0.10",
        "printlab.rates.acquisition-per-new-customer=0.00"
})
class A1MiniPricingTest {
    @Autowired PricingService pricing;
    @Autowired AppProperties properties;
    @Autowired Json json;

    @Test
    void ownerRatesProduceIndependentlyCalculatedCostsAndAmortization() {
        var quote = pricing.calculate("pla-black", Fixtures.estimate(), true, false, "");
        assertThat(quote.costs().material()).isEqualByComparingTo("75.00");
        assertThat(quote.costs().electricity()).isEqualByComparingTo("6.83");
        assertThat(quote.costs().depreciation()).isEqualByComparingTo("35.00");
        assertThat(quote.costs().maintenance()).isEqualByComparingTo("17.50");
        assertThat(quote.costs().failureReserve()).isEqualByComparingTo("14.93");
        assertThat(quote.costs().total()).isEqualByComparingTo("184.26");
        assertThat(quote.publicPrice().regularPrintPrice()).isEqualByComparingTo("460.00");
        assertThat(quote.publicPrice().launchFee()).isEqualByComparingTo("150.00");
        assertThat(quote.publicPrice().total()).isEqualByComparingTo("610.00");
        assertThat(quote.economics().totalCost()).isEqualByComparingTo("226.96");
        assertThat(quote.economics().contribution()).isEqualByComparingTo("383.04");
        assertThat(quote.economics().printingContribution()).isEqualByComparingTo("233.04");
        assertThat(quote.economics().profitMarkup()).isGreaterThanOrEqualTo(BigDecimal.ONE);
        // The old 15 RUB/h fixture is not added to the capital-based 10 RUB/h.
        assertThat(properties.rates().effectiveDepreciationPerHour()).isEqualByComparingTo("10.00");

        // Owner's example, including 22.50 RUB commission and 30.00 RUB tax in the 300 RUB cost.
        Estimate ownerExample = new Estimate(new BigDecimal("131.92"), BigDecimal.ZERO, new BigDecimal("210"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        var exactQuote = pricing.calculate("pla-black", ownerExample, false, false, "");
        assertThat(exactQuote.economics().totalCost()).isEqualByComparingTo("300.00");
        assertThat(exactQuote.economics().printingContribution()).isEqualByComparingTo("300.00");
        assertThat(exactQuote.publicPrice().launchFee()).isEqualByComparingTo("150.00");
        assertThat(exactQuote.publicPrice().total()).isEqualByComparingTo("750.00");
    }

    @Test
    void promotionExcludesLaunchAndFeesAndTaxIncludeEntirePayment() {
        var quote = pricing.calculate("pla-black", Fixtures.estimate(), true, true, "FIRST50");
        assertThat(quote.publicPrice().discount()).isEqualByComparingTo("230.00");
        assertThat(quote.publicPrice().total()).isEqualByComparingTo("380.00");
        assertThat(quote.economics().paymentFee()).isEqualByComparingTo("11.40");
        assertThat(quote.economics().estimatedTax()).isEqualByComparingTo("15.20");
        assertThat(quote.economics().contribution()).isEqualByComparingTo("169.14");
        assertThat(quote.economics().printingContribution()).isEqualByComparingTo("19.14");
        assertThat(quote.economics().minimumContribution()).isEqualByComparingTo("255.43");
        assertThat(quote.economics().needsReview()).isTrue();

        // A one-kopeck difference crosses the percentage threshold.
        var accepted = pricing.economics(quote.costs(), new BigDecimal("476.42"), properties.rates(), new BigDecimal("150"));
        var rejected = pricing.economics(quote.costs(), new BigDecimal("476.41"), properties.rates(), new BigDecimal("150"));
        assertThat(accepted.printingContribution()).isEqualByComparingTo("108.81");
        assertThat(accepted.needsReview()).isFalse();
        assertThat(rejected.printingContribution()).isEqualByComparingTo("108.80");
        assertThat(rejected.needsReview()).isTrue();
    }

    @Test
    void launchIsChargedOnceForSeveralPlatesWithoutMultiplyingTotalSlicerConsumption() {
        Estimate batch = new Estimate(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("210"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 2);
        var quote = pricing.calculate("pla-black", batch, true, true, "");
        assertThat(quote.costs().material()).isEqualByComparingTo("75.00");
        assertThat(quote.publicPrice().regularPrintPrice()).isEqualByComparingTo("460.00");
        assertThat(quote.publicPrice().launchFee()).isEqualByComparingTo("150.00");
        assertThat(quote.publicPrice().discount()).isEqualByComparingTo("230.00");
        assertThat(quote.publicPrice().total()).isEqualByComparingTo("380.00");
    }

    @Test
    void legacySnapshotsRetainOldAmortizationAndNoNewLaunchFee() {
        String oldRates = """
                {"version":"old","electricityPerKwh":7,"printerKw":0.15,
                "depreciationPerHour":15,"maintenancePerHour":5,"laborPerHour":0,
                "packaging":35,"acquisitionPerNewCustomer":80,"failureProbability":0.05,
                "paymentRate":0.03,"paymentFixed":0,"taxRate":0.04,"targetMargin":0.4,
                "minimumPrintPrice":400,"minimumContribution":100,"quoteHours":24}
                """;
        CostRates rates = json.read(oldRates, CostRates.class);
        assertThat(rates.effectiveDepreciationPerHour()).isEqualByComparingTo("15");
        assertThat(rates.launchFee()).isZero();
        assertThat(rates.targetProfitMarkup()).isNull();
        assertThat(rates.minimumProfitMarkup()).isZero();
        PublicPrice price = json.read("""
                {"regularPrintPrice":550,"discount":0,"deliveryPrice":0,
                "total":550,"currency":"RUB","promotionNote":"old"}
                """, PublicPrice.class);
        assertThat(price.launchFee()).isZero();
        Estimate estimate = json.read("""
                {"grams":75,"extraWasteGrams":0,"printMinutes":210,"setupMinutes":0,
                "finishingMinutes":0,"deliveryCost":0,"deliveryPrice":0}
                """, Estimate.class);
        assertThat(estimate.plateCount()).isEqualTo(1);
    }
}
