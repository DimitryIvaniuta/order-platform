package com.github.dimitryivaniuta.payment.api.dto;

import java.math.BigDecimal;
import java.util.Currency;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Money represented in minor units, with a decimal projection for APIs. */
@Jacksonized
@Builder
public record MoneyDto(
        long amountMinor,
        String currencyCode,
        BigDecimal amount
) {
    public static MoneyDto of(long amountMinor, String currencyCode) {
        int scale = fractionDigits(currencyCode);
        return MoneyDto.builder()
                .amountMinor(amountMinor)
                .currencyCode(currencyCode)
                .amount(BigDecimal.valueOf(amountMinor, scale))
                .build();
    }

    public static int fractionDigits(String currencyCode) {
        try { return Currency.getInstance(currencyCode).getDefaultFractionDigits(); }
        catch (Exception e) { return 2; } // fallback for custom codes
    }
}
