package com.github.dimitryivaniuta.payment.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

import com.github.dimitryivaniuta.payment.domain.model.CaptureEntity;
import com.github.dimitryivaniuta.payment.domain.model.LedgerEntryEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.model.RefundEntity;
import com.github.dimitryivaniuta.payment.domain.repo.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * LedgerService posts balanced double-entry journal lines for:
 * <ul>
 *   <li><b>Authorization</b>: AR ←→ PSP_CLEARING</li>
 *   <li><b>Capture</b>: PSP_CLEARING ←→ REVENUE</li>
 *   <li><b>Refund</b>: REFUNDS_PAYABLE ←→ PSP_CLEARING</li>
 * </ul>
 *
 * <p>All amounts are minor units. Currency must be ISO-4217 (uppercase, 3 letters).
 * This service performs only persistence of journal entries; wrap calls in your
 * outer reactive transaction (TransactionalOperator) together with the business updates.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    // ---- Chart of accounts (adjust to your chart if needed) ----
    public static final String ACCOUNTS_RECEIVABLE = "AR";
    public static final String PSP_CLEARING        = "PSP_CLEARING";
    public static final String REVENUE             = "REVENUE";
    public static final String REFUNDS_PAYABLE     = "REFUNDS_PAYABLE";

    // ---- Dependencies (alphabetical) ----
    private final LedgerEntryRepository ledgerRepo;

    /* ======================================================================
       PUBLIC API
       ====================================================================== */

    /**
     * Post authorization journal:
     * <pre>
     *   DR AR                amount
     *   CR PSP_CLEARING      amount
     * </pre>
     */
    public Mono<Void> postAuthorization(final PaymentEntity payment) {
        Objects.requireNonNull(payment, "payment");
        final String tenantId   = requireNonBlank(payment.getTenantId(), "tenantId");
        final Long   paymentId  = requireNonNull(payment.getId(), "paymentId");
        final long   amount     = requirePositive(payment.getAmountMinor(), "amountMinor");
        final String currency   = normalizeCurrency(payment.getCurrencyCode());

        final UUID journal = UUID.randomUUID();
        final LocalDate bookingDate = LocalDate.now();
        final Instant ts = Instant.now();

        var debitAR = baseEntry(tenantId, journal, bookingDate, ts)
                .accountCode(ACCOUNTS_RECEIVABLE)
                .currencyCode(currency)
                .debitMinor(amount)
                .creditMinor(0)
                .paymentId(paymentId)
                .build();

        var creditClearing = baseEntry(tenantId, journal, bookingDate, ts)
                .accountCode(PSP_CLEARING)
                .currencyCode(currency)
                .debitMinor(0)
                .creditMinor(amount)
                .paymentId(paymentId)
                .build();

        return persistBalanced(journal, amount, debitAR, creditClearing);
    }

    /**
     * Post capture journal:
     * <pre>
     *   DR PSP_CLEARING      amount
     *   CR REVENUE           amount
     * </pre>
     */
    public Mono<Void> postCapture(final PaymentEntity payment, final CaptureEntity capture) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(capture, "capture");

        final String tenantId   = requireNonBlank(payment.getTenantId(), "tenantId");
        final Long   paymentId  = requireNonNull(payment.getId(), "paymentId");
        final Long   captureId  = requireNonNull(capture.getId(), "captureId");
        final long   amount     = requirePositive(capture.getAmountMinor(), "capture.amountMinor");
        final String currency   = normalizeCurrency(capture.getCurrencyCode());

        final UUID journal = UUID.randomUUID();
        final LocalDate bookingDate = LocalDate.now();
        final Instant ts = Instant.now();

        var debitClearing = baseEntry(tenantId, journal, bookingDate, ts)
                .accountCode(PSP_CLEARING)
                .currencyCode(currency)
                .debitMinor(amount)
                .creditMinor(0)
                .paymentId(paymentId)
                .captureId(captureId)
                .build();

        var creditRevenue = baseEntry(tenantId, journal, bookingDate, ts)
                .accountCode(REVENUE)
                .currencyCode(currency)
                .debitMinor(0)
                .creditMinor(amount)
                .paymentId(paymentId)
                .captureId(captureId)
                .build();

        return persistBalanced(journal, amount, debitClearing, creditRevenue);
    }

    /**
     * Post refund journal:
     * <pre>
     *   DR REFUNDS_PAYABLE   amount
     *   CR PSP_CLEARING      amount
     * </pre>
     */
    public Mono<Void> postRefund(final PaymentEntity payment, final RefundEntity refund) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(refund, "refund");

        final String tenantId   = requireNonBlank(payment.getTenantId(), "tenantId");
        final Long   paymentId  = requireNonNull(payment.getId(), "paymentId");
        final Long   refundId   = requireNonNull(refund.getId(), "refundId");
        final long   amount     = requirePositive(refund.getAmountMinor(), "refund.amountMinor");
        final String currency   = normalizeCurrency(refund.getCurrencyCode());

        final UUID journal = UUID.randomUUID();
        final LocalDate bookingDate = LocalDate.now();
        final Instant ts = Instant.now();

        var debitRefunds = baseEntry(tenantId, journal, bookingDate, ts)
                .accountCode(REFUNDS_PAYABLE)
                .currencyCode(currency)
                .debitMinor(amount)
                .creditMinor(0)
                .paymentId(paymentId)
                .refundId(refundId)
                .build();

        var creditClearing = baseEntry(tenantId, journal, bookingDate, ts)
                .accountCode(PSP_CLEARING)
                .currencyCode(currency)
                .debitMinor(0)
                .creditMinor(amount)
                .paymentId(paymentId)
                .refundId(refundId)
                .build();

        return persistBalanced(journal, amount, debitRefunds, creditClearing);
    }

    /* ======================================================================
       INTERNALS
       ====================================================================== */

    private LedgerEntryEntity.LedgerEntryEntityBuilder baseEntry(
            final String tenantId,
            final UUID journalId,
            final LocalDate bookingDate,
            final Instant timestamp
    ) {
        return LedgerEntryEntity.builder()
                .tenantId(tenantId)
                .journalId(journalId)
                .bookingDate(bookingDate)
                .createdAt(timestamp)
                .updatedAt(timestamp);
    }

    private Mono<Void> persistBalanced(final UUID journal, final long amount,
                                       final LedgerEntryEntity debit, final LedgerEntryEntity credit) {
        // Sanity check: ensure balanced & strictly one debit/one credit
        if (debit.getDebitMinor() <= 0 || credit.getCreditMinor() <= 0
                || debit.getCreditMinor() != 0 || credit.getDebitMinor() != 0
                || debit.getDebitMinor() != credit.getCreditMinor()) {
            return Mono.error(new IllegalStateException("Unbalanced journal " + journal));
        }

        return ledgerRepo.saveAll(Flux.just(debit, credit))
                .then()
                .doOnSuccess(v -> log.debug("Ledger posted journal={} amount={}", journal, amount));
    }

    private static String normalizeCurrency(String code) {
        final String cur = requireNonBlank(code, "currencyCode").toUpperCase();
        // Validate ISO-4217 (will throw for unknown)
        try { Currency.getInstance(cur); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ISO currency code: " + cur, e);
        }
        return cur;
    }

    private static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " must not be null");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long requirePositive(long v, String name) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
        return v;
    }
}
