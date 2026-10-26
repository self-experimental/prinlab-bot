package ru.printlab.config;

import java.math.BigDecimal;

public record PrinterProfile(String model, BigDecimal widthMm, BigDecimal depthMm, BigDecimal heightMm,
                             BigDecimal nozzleDiameterMm, int maxColors) {
}
