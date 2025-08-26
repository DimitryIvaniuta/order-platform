package com.github.dimitryivaniuta.payment.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the FakePaymentProvider.
 * Example (application.yml):
 * provider:
 *   fake:
 *     enabled: true
 *     min-latency: 10ms
 *     max-latency: 50ms
 *     max-amount-minor: 10000000
 *     risk-modulo: 13
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "provider.fake")
public class FakePaymentProviderProperties {

    /** Switch everything on/off (if off → always succeed). */
    private boolean enabled = true;

    /** Artificial latency bounds for reactive variants. */
    private Duration minLatency = Duration.ofMillis(10);
    private Duration maxLatency = Duration.ofMillis(50);

    /** Upper bound for authorized/captured/refunded amounts (minor units). */
    @Min(1)
    private long maxAmountMinor = 10_000_000L;

    /** Deterministic “risk block” rule: amounts divisible by this value will fail. */
    @Min(2)
    private int riskModulo = 13;

    /** Optional currency regex to validate ISO codes. */
//    @Pattern(regexp = "^[A-Z]{3}$")
    private String currencyPattern = "^[A-Z]{3}$";
}
