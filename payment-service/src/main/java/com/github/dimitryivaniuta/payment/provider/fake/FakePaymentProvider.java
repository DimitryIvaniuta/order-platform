package com.github.dimitryivaniuta.payment.provider.fake;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.github.dimitryivaniuta.payment.config.PaymentProviderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Deterministic, configurable fake PSP adapter.
 * - Uses provider.fake.* from unified ProviderProperties.
 * - Provides both sync (used by services today) and reactive variants.
 * - Pure minor-units; uppercase ISO currency enforced by regex from config.
 */
//@Component
@RequiredArgsConstructor
public class FakePaymentProvider {

    /** Result shape used by services. */
    public record Result(boolean authorized, String externalRef, String failureCode, String failureReason) {}

    private final PaymentProviderProperties props;

  /* =======================================================
     AUTHORIZATION
     ======================================================= */

    /** Synchronous authorize (used by PaymentService). */
    public Result authorize(long amountMinor, String currencyCode) {
        var cfg = props.getFake();

        // Disabled ⇒ always succeed
        if (!cfg.isEnabled()) return ok("AUTH-" + uuid());

        // Validation & deterministic failures
        if (amountMinor <= 0) return fail("AMOUNT_INVALID", "Amount must be > 0");
        if (currencyCode == null || !currencyCode.matches(cfg.getCurrencyPattern()))
            return fail("CURRENCY_INVALID", "Currency must match " + cfg.getCurrencyPattern());
        if (amountMinor > cfg.getMaxAmountMinor())
            return fail("AMOUNT_TOO_LARGE", "Amount exceeds limit " + cfg.getMaxAmountMinor());
        if (amountMinor % cfg.getRiskModulo() == 0)
            return fail("RISK_BLOCKED", "Transaction blocked by deterministic risk rule");

        return ok("AUTH-" + uuid());
    }

    /** Reactive authorize with artificial latency. */
    public Mono<Result> authorizeMono(long amountMinor, String currencyCode) {
        return Mono.fromCallable(() -> authorize(amountMinor, currencyCode))
                .delayElement(randomLatency());
    }

  /* =======================================================
     CAPTURE
     ======================================================= */

    public Result capture(long amountMinor, String currencyCode, String authRef) {
        var cfg = props.getFake();

        if (!cfg.isEnabled()) return ok("CAP-" + uuid());

        if (authRef == null || authRef.isBlank())
            return fail("AUTH_REF_MISSING", "Authorization reference required");
        if (amountMinor <= 0) return fail("AMOUNT_INVALID", "Amount must be > 0");
        if (currencyCode == null || !currencyCode.matches(cfg.getCurrencyPattern()))
            return fail("CURRENCY_INVALID", "Currency must match " + cfg.getCurrencyPattern());
        if (amountMinor > cfg.getMaxAmountMinor())
            return fail("AMOUNT_TOO_LARGE", "Amount exceeds limit " + cfg.getMaxAmountMinor());
        if (amountMinor % cfg.getRiskModulo() == 0)
            return fail("RISK_BLOCKED", "Transaction blocked by deterministic risk rule");

        return ok("CAP-" + uuid());
    }

    public Mono<Result> captureMono(long amountMinor, String currencyCode, String authRef) {
        return Mono.fromCallable(() -> capture(amountMinor, currencyCode, authRef))
                .delayElement(randomLatency());
    }

  /* =======================================================
     REFUND
     ======================================================= */

    public Result refund(long amountMinor, String currencyCode, String captureRef) {
        var cfg = props.getFake();

        if (!cfg.isEnabled()) return ok("REF-" + uuid());

        if (captureRef == null || captureRef.isBlank())
            return fail("CAPTURE_REF_MISSING", "Capture reference required");
        if (amountMinor <= 0) return fail("AMOUNT_INVALID", "Amount must be > 0");
        if (currencyCode == null || !currencyCode.matches(cfg.getCurrencyPattern()))
            return fail("CURRENCY_INVALID", "Currency must match " + cfg.getCurrencyPattern());
        if (amountMinor > cfg.getMaxAmountMinor())
            return fail("AMOUNT_TOO_LARGE", "Amount exceeds limit " + cfg.getMaxAmountMinor());
        if (amountMinor % cfg.getRiskModulo() == 0)
            return fail("RISK_BLOCKED", "Transaction blocked by deterministic risk rule");

        return ok("REF-" + uuid());
    }

    public Mono<Result> refundMono(long amountMinor, String currencyCode, String captureRef) {
        return Mono.fromCallable(() -> refund(amountMinor, currencyCode, captureRef))
                .delayElement(randomLatency());
    }

  /* =======================================================
     Internals
     ======================================================= */

    private static Result ok(String ref)  { return new Result(true,  ref,  null, null); }
    private static Result fail(String c, String r) { return new Result(false, null, c,    r   ); }

    private static String uuid() { return UUID.randomUUID().toString(); }

    private Duration randomLatency() {
        var cfg = props.getFake();
        long min = Math.max(0L, cfg.getMinLatency().toMillis());
        long max = Math.max(min, cfg.getMaxLatency().toMillis());
        long range = max - min;
        long jitter = (range == 0) ? 0 : ThreadLocalRandom.current().nextLong(range + 1);
        return Duration.ofMillis(min + jitter);
    }
}
