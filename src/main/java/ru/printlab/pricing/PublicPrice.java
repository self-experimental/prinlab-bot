package ru.printlab.pricing;

import java.math.BigDecimal;

public record PublicPrice(BigDecimal regularPrintPrice, BigDecimal discount, BigDecimal deliveryPrice,
                          BigDecimal total, String currency, String promotionNote, BigDecimal launchFee) {
    public PublicPrice {
        if (launchFee == null) {
            launchFee = BigDecimal.ZERO;
        }
    }
}
