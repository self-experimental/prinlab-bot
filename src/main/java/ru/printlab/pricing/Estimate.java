package ru.printlab.pricing;

import ru.printlab.common.BusinessException;
import ru.printlab.common.Money;

import java.math.BigDecimal;

/** All quantities refer to the entire batch, including supports from the slicer. */
public record Estimate(BigDecimal grams, BigDecimal extraWasteGrams, BigDecimal printMinutes,
                       BigDecimal setupMinutes, BigDecimal finishingMinutes,
                       BigDecimal deliveryCost, BigDecimal deliveryPrice, Integer plateCount) {
    public Estimate {
        // Compatibility with existing quote snapshots and seven-field operator input.
        if (plateCount == null) {
            plateCount = 1;
        }
    }

    public Estimate(BigDecimal grams, BigDecimal extraWasteGrams, BigDecimal printMinutes,
                    BigDecimal setupMinutes, BigDecimal finishingMinutes,
                    BigDecimal deliveryCost, BigDecimal deliveryPrice) {
        this(grams, extraWasteGrams, printMinutes, setupMinutes, finishingMinutes, deliveryCost, deliveryPrice, 1);
    }

    public void validate() {
        Money.nonNegative(grams, "Масса");
        Money.nonNegative(extraWasteGrams, "Дополнительные отходы");
        Money.nonNegative(printMinutes, "Время печати");
        Money.nonNegative(setupMinutes, "Подготовка");
        Money.nonNegative(finishingMinutes, "Обработка");
        Money.nonNegative(deliveryCost, "Себестоимость доставки");
        Money.nonNegative(deliveryPrice, "Цена доставки");
        if (grams.signum() == 0 || printMinutes.signum() == 0) {
            throw new BusinessException("Масса и время печати должны быть больше нуля.");
        }
        if (plateCount < 1 || plateCount > 100) {
            throw new BusinessException("Количество пластин: от 1 до 100.");
        }
    }
}
