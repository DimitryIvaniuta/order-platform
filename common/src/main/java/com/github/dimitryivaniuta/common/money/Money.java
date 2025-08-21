package com.github.dimitryivaniuta.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

public record Money(String currency, long minor) {
    public Currency jdk() { return Currency.getInstance(currency); }
    public int fractionDigits() { return jdk().getDefaultFractionDigits(); }

    public BigDecimal asDecimal() {
        int scale = Math.max(0, fractionDigits()); // JPY = 0
        return BigDecimal.valueOf(minor, scale);
    }

    public static Money ofDecimal(String currency, BigDecimal amount) {
        Currency cur = Currency.getInstance(currency);
        int scale = Math.max(0, cur.getDefaultFractionDigits());
        long minor = amount.setScale(scale, RoundingMode.HALF_UP)
                .movePointRight(scale).longValueExact();
        return new Money(currency, minor);
    }
}
