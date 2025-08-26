package com.github.dimitryivaniuta.payment.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.github.dimitryivaniuta.payment.config.FakePaymentProviderProperties;
import lombok.RequiredArgsConstructor;
//import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Deterministic, configurable fake PSP adapter.
 * Provides sync methods (used by current PaymentService) and reactive variants with artificial latency.
 */
@Component
@RequiredArgsConstructor
//@EnableConfigurationProperties(FakePaymentProviderProperties.class)
public class FakePaymentProvider {

    public record Result(boolean authorized, String externalRef, String failureCode, String failureReason) {}

    private final FakePaymentProviderProperties props;

  /* ===========================
     AUTHORIZATION
     =========================== */

    /** Synchronous authorize used by current PaymentService. */
    public Result authorize(long amountMinor, String currencyCode) {
        // Disabled → always succeed
        if (!props.isEnabled()) {
            return ok("AUTH-" + UUID.randomUUID());
        }

        // Validation & deterministic failures
        if (amountMinor <= 0) return fail("AMOUNT_INVALID", "Amount must be > 0");
        if (currencyCode == null || !currencyCode.matches(props.getCurrencyPattern()))
            return fail("CURRENCY_INVALID", "Currency must match " + props.getCurrencyPattern());
        if (amountMinor > props.getMaxAmountMinor())
            return fail("AMOUNT_TOO_LARGE", "Amount exceeds limit " + props.getMaxAmountMinor());
        if (amountMinor % props.getRiskModulo() == 0)
            return fail("RISK_BLOCKED", "Transaction blocked by deterministic risk rule");

        return ok("AUTH-" + UUID.randomUUID());
    }

    /** Reactive authorize with artificial latency. */
    public Mono<Result> authorizeMono(long amountMinor, String currencyCode) {
        return Mono.defer(() -> Mono.just(authorize(amountMinor, currencyCode)))
                .delayElement(randomLatency());
    }

  /* ===========================
     CAPTURE
     =========================== */

    /** Synchronous capture helper (not used by current PaymentService, but ready). */
    public Result capture(long amountMinor, String currencyCode, String authRef) {
        if (!props.isEnabled()) {
            return ok("CAP-" + UUID.randomUUID());
        }
        if (authRef == null || authRef.isBlank())
            return fail("AUTH_REF_MISSING", "Authorization reference required");
        if (amountMinor <= 0) return fail("AMOUNT_INVALID", "Amount must be > 0");
        if (currencyCode == null || !currencyCode.matches(props.getCurrencyPattern()))
            return fail("CURRENCY_INVALID", "Currency must match " + props.getCurrencyPattern());
        if (amountMinor > props.getMaxAmountMinor())
            return fail("AMOUNT_TOO_LARGE", "Amount exceeds limit " + props.getMaxAmountMinor());
        if (amountMinor % props.getRiskModulo() == 0)
            return fail("RISK_BLOCKED", "Transaction blocked by deterministic risk rule");

        return ok("CAP-" + UUID.randomUUID());
    }

    /** Reactive capture with artificial latency. */
    public Mono<Result> captureMono(long amountMinor, String currencyCode, String authRef) {
        return Mono.defer(() -> Mono.just(capture(amountMinor, currencyCode, authRef)))
                .delayElement(randomLatency());
    }

  /* ===========================
     REFUND
     =========================== */

    /** Synchronous refund helper (not used by current PaymentService, but ready). */
    public Result refund(long amountMinor, String currencyCode, String captureRef) {
        if (!props.isEnabled()) {
            return ok("REF-" + UUID.randomUUID());
        }
        if (captureRef == null || captureRef.isBlank())
            return fail("CAPTURE_REF_MISSING", "Capture reference required");
        if (amountMinor <= 0) return fail("AMOUNT_INVALID", "Amount must be > 0");
        if (currencyCode == null || !currencyCode.matches(props.getCurrencyPattern()))
            return fail("CURRENCY_INVALID", "Currency must match " + props.getCurrencyPattern());
        if (amountMinor > props.getMaxAmountMinor())
            return fail("AMOUNT_TOO_LARGE", "Amount exceeds limit " + props.getMaxAmountMinor());
        if (amountMinor % props.getRiskModulo() == 0)
            return fail("RISK_BLOCKED", "Transaction blocked by deterministic risk rule");

        return ok("REF-" + UUID.randomUUID());
    }

    /** Reactive refund with artificial latency. */
    public Mono<Result> refundMono(long amountMinor, String currencyCode, String captureRef) {
        return Mono.defer(() -> Mono.just(refund(amountMinor, currencyCode, captureRef)))
                .delayElement(randomLatency());
    }

  /* ===========================
     Internals
     =========================== */

    private static Result ok(String ref) {
        return new Result(true, ref, null, null);
    }

    private static Result fail(String code, String reason) {
        return new Result(false, null, code, reason);
    }

    private Duration randomLatency() {
        long min = Math.max(0L, props.getMinLatency().toMillis());
        long max = Math.max(min, props.getMaxLatency().toMillis());
        long delta = max - min;
        long jitter = (delta == 0) ? 0 : ThreadLocalRandom.current().nextLong(delta + 1);
        return Duration.ofMillis(min + jitter);
    }
}
