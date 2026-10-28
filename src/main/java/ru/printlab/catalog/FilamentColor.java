package ru.printlab.catalog;

import java.util.UUID;

public record FilamentColor(UUID id, String name, String surface, String manufacturer,
                            String paletteNumber, String bambuArticle, boolean available, boolean deleted, java.math.BigDecimal spoolPrice, int spoolWeightGrams) {
    public FilamentColor(UUID id, String name, String surface, String manufacturer, String paletteNumber,
                         String bambuArticle, boolean available, boolean deleted) {
        this(id, name, surface, manufacturer, paletteNumber, bambuArticle, available, deleted, null, 1000);
    }

    public java.math.BigDecimal costPerKg() {
        if (spoolPrice == null || spoolPrice.signum() <= 0 || spoolWeightGrams <= 0)
            throw new ru.printlab.common.BusinessException("Для этого цвета ещё не указана стоимость катушки. Команда PrintLab проверит каталог.");
        return spoolPrice.multiply(new java.math.BigDecimal("1000"))
                .divide(java.math.BigDecimal.valueOf(spoolWeightGrams), 8, java.math.RoundingMode.HALF_UP);
    }

    public String label() {
        String label = name;
        if (surface != null && !surface.isBlank()) {
            label += ", " + surface.toLowerCase(java.util.Locale.ROOT);
        }
        return label;
    }
}
