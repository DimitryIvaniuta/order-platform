package com.github.dimitryivaniuta.payment.util;

import java.math.BigDecimal;

public final class MoneyUtil {

    public static long toMinor(BigDecimal amount, int fractionDigits) {
        if (amount == null) throw new IllegalArgumentException("amount is null");
        return amount
                .setScale(fractionDigits, java.math.RoundingMode.HALF_UP)
                .movePointRight(fractionDigits)
                .longValueExact();
    }
}
