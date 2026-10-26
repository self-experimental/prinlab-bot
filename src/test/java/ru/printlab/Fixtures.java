package ru.printlab;

import ru.printlab.pricing.Estimate;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Fixtures {
    private Fixtures() {
    }

    public static Estimate estimate() {
        return new Estimate(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("210"),
                new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static byte[] stl() {
        ByteBuffer buffer = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(80, 1);
        buffer.putFloat(108, 10);
        buffer.putFloat(124, 10);
        return buffer.array();
    }
}
