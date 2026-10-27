package ru.printlab.catalog;

import java.util.UUID;

public record FilamentColor(UUID id, String name, String surface, String manufacturer,
                            String paletteNumber, String bambuArticle, boolean available, boolean deleted) {
    public String label() {
        String label = name;
        if (surface != null && !surface.isBlank()) {
            label += ", " + surface.toLowerCase(java.util.Locale.ROOT);
        }
        return label;
    }
}
