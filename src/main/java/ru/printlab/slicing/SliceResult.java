package ru.printlab.slicing;

import ru.printlab.pricing.Estimate;

public record SliceResult(Estimate estimate, String provenance) {
}
