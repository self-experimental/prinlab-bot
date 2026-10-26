package ru.printlab.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

public record CostRates(
        String version,
        BigDecimal electricityPerKwh,
        BigDecimal printerKw,
        BigDecimal depreciationPerHour,
        BigDecimal maintenancePerHour,
        BigDecimal laborPerHour,
        BigDecimal packaging,
        BigDecimal acquisitionPerNewCustomer,
        BigDecimal failureProbability,
        BigDecimal paymentRate,
        BigDecimal paymentFixed,
        BigDecimal taxRate,
        BigDecimal targetMargin,
        BigDecimal minimumPrintPrice,
        BigDecimal minimumContribution,
        int quoteHours,
        BigDecimal printerPurchaseCost,
        BigDecimal printerLifetimeHours,
        BigDecimal launchFee,
        Boolean discountLaunchFee,
        Boolean launchFeePerOrder,
        BigDecimal targetProfitMarkup,
        BigDecimal minimumProfitMarkup) {
    @ConstructorBinding
    public CostRates {
        // Old saved quotes did not contain these fields.
        if (printerPurchaseCost == null) {
            printerPurchaseCost = BigDecimal.ZERO;
        }
        if (printerLifetimeHours == null) {
            printerLifetimeHours = BigDecimal.ZERO;
        }
        if (launchFee == null) {
            launchFee = BigDecimal.ZERO;
        }
        if (discountLaunchFee == null) {
            discountLaunchFee = false;
        }
        if (launchFeePerOrder == null) {
            launchFeePerOrder = false;
        }
        if (minimumProfitMarkup == null) {
            minimumProfitMarkup = BigDecimal.ZERO;
        }
    }

    public BigDecimal effectiveDepreciationPerHour() {
        if (printerPurchaseCost.signum() > 0 && printerLifetimeHours.signum() > 0) {
            return printerPurchaseCost.divide(printerLifetimeHours, 12, RoundingMode.HALF_UP);
        }
        return depreciationPerHour;
    }

    public CostRates(String version, BigDecimal electricityPerKwh, BigDecimal printerKw,
                     BigDecimal depreciationPerHour, BigDecimal maintenancePerHour, BigDecimal laborPerHour,
                     BigDecimal packaging, BigDecimal acquisitionPerNewCustomer, BigDecimal failureProbability,
                     BigDecimal paymentRate, BigDecimal paymentFixed, BigDecimal taxRate, BigDecimal targetMargin,
                     BigDecimal minimumPrintPrice, BigDecimal minimumContribution, int quoteHours) {
        this(version, electricityPerKwh, printerKw, depreciationPerHour, maintenancePerHour, laborPerHour,
                packaging, acquisitionPerNewCustomer, failureProbability, paymentRate, paymentFixed, taxRate,
                targetMargin, minimumPrintPrice, minimumContribution, quoteHours,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, false, null, BigDecimal.ZERO);
    }
}
