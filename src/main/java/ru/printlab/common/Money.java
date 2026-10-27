package ru.printlab.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() {
    }

    public static BigDecimal rub(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal hours(BigDecimal minutes) {
        return minutes.divide(new BigDecimal("60"), 12, RoundingMode.HALF_UP);
    }

    public static BigDecimal nonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("10000000")) > 0) {
            throw new BusinessException(name + ": нужно число от 0 до 10000000.");
        }
        return value;
    }

    public static String format(BigDecimal value) {
        return rub(value).toPlainString().replace('.', ',') + " ₽";
    }
}
