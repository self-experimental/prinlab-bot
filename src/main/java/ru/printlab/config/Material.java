package ru.printlab.config;

import java.math.BigDecimal;

public record Material(String label, BigDecimal costPerKg, boolean available) {
}
