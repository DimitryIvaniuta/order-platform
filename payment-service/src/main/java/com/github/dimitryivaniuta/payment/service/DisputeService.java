package com.github.dimitryivaniuta.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.github.dimitryivaniuta.payment.domain.model.DisputeEntity;
import com.github.dimitryivaniuta.payment.domain.model.DisputeStage;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.repo.DisputeRepository;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentRepository;
import com.github.dimitryivaniuta.payment.outbox.OutboxStore;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Dispute/chargeback lifecycle service.
 * <ul>
 *   <li>Open (idempotent on (tenantId, psp, pspDisputeId))</li>
 *   <li>Submit evidence / move to ARBITRATION / other stage transitions</li>
 *   <li>Close with outcome (WON / LOST / CANCELLED / CLOSED)</li>
 *   <li>Emit domain events via transactional outbox</li>
 * </ul>
 *
 * All amounts are minor units and currencies must be ISO-4217 uppercase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    private final PaymentRepository paymentRepo;
    private final DisputeRepository disputeRepo;
    private final OutboxStore outbox;
    private final ObjectMapper om;
    private final TransactionalOperator tx;
    private final DatabaseClient db;

    // Event type strings (downstream consumers rely on these)
    private static final String EVT_OPENED = "PAYMENT_CHARGEBACK_OPENED";
    private static final String EVT_CLOSED = "PAYMENT_CHARGEBACK_CLOSED";

    /* ======================================================================
       OPEN
       ====================================================================== */

    /**
     * Open a dispute (idempotent by tenant+psp+pspDisputeId).
     * Creates the row if not present; otherwise returns the existing one unchanged.
     */
    public Mono<DisputeEntity> open(
            String tenantId,
            long paymentId,
            String psp,
            String pspDisputeId,
            String reasonCode,
            long amountMinor,
            String currencyCode
    ) {
        final String t = requireNonBlank(tenantId, "tenantId");
        final String provider = requireNonBlank(psp, "psp");
        final String providerDisputeId = requireNonBlank(pspDisputeId, "pspDisputeId");
        final long amount = requireNonNegative(amountMinor, "amountMinor");
        final String currency = normalizeCurrency(currencyCode);

        return findByTenantAndProviderDispute(t, provider, providerDisputeId)
                .switchIfEmpty(
                        // Create new (OPENED) and emit event atomically with outbox
                        tx.transactional(
                                paymentRepo.findById(paymentId)
                                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found: " + paymentId)))
                                        .flatMap(pmt -> {
                                            var now = Instant.now();
                                            var entity = DisputeEntity.builder()
                                                    .tenantId(t)
                                                    .paymentId(pmt.getId())
                                                    .psp(provider)
                                                    .pspDisputeId(providerDisputeId)
                                                    .reasonCode(reasonCode)
                                                    .amountMinor(amount)
                                                    .currencyCode(currency)
                                                    .stage(DisputeStage.OPENED)
                                                    .openedAt(now)
                                                    .createdAt(now)
                                                    .updatedAt(now)
                                                    .build();
                                            return disputeRepo.save(entity)
                                                    .flatMap(saved -> emitOpened(pmt, saved).thenReturn(saved));
                                        })
                        )
                );
    }

    /* ======================================================================
       EVIDENCE / ARBITRATION / GENERIC STAGE TRANSITIONS
       ====================================================================== */

    /**
     * Move a dispute to EVIDENCE_SUBMITTED stage.
     */
    public Mono<DisputeEntity> submitEvidence(String tenantId, String psp, String pspDisputeId) {
        return transition(tenantId, psp, pspDisputeId, DisputeStage.EVIDENCE_SUBMITTED, null);
    }

    /**
     * Move a dispute to ARBITRATION stage.
     */
    public Mono<DisputeEntity> toArbitration(String tenantId, String psp, String pspDisputeId) {
        return transition(tenantId, psp, pspDisputeId, DisputeStage.ARBITRATION, null);
    }

    /**
     * Generic transition to any non-terminal stage (OPENED/EVIDENCE_SUBMITTED/ARBITRATION).
     */
    public Mono<DisputeEntity> transition(String tenantId, String psp, String pspDisputeId,
                                          DisputeStage next, String newReasonCode) {
        if (next == null) return Mono.error(new IllegalArgumentException("next stage must not be null"));
        if (isTerminal(next)) return Mono.error(new IllegalArgumentException("Use close(..) for terminal stages"));

        final String t = requireNonBlank(tenantId, "tenantId");
        final String provider = requireNonBlank(psp, "psp");
        final String providerDisputeId = requireNonBlank(pspDisputeId, "pspDisputeId");

        return tx.transactional(
                findByTenantAndProviderDispute(t, provider, providerDisputeId)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Dispute not found")))
                        .flatMap(d -> {
                            d.setStage(next);
                            if (newReasonCode != null && !newReasonCode.isBlank()) {
                                d.setReasonCode(newReasonCode);
                            }
                            d.setUpdatedAt(Instant.now());
                            return disputeRepo.save(d);
                        })
        );
    }

    /* ======================================================================
       CLOSE (WON / LOST / CLOSED / CANCELLED)
       ====================================================================== */

    public Mono<DisputeEntity> closeWon(String tenantId, String psp, String pspDisputeId) {
        return close(tenantId, psp, pspDisputeId, DisputeStage.WON);
    }

    public Mono<DisputeEntity> closeLost(String tenantId, String psp, String pspDisputeId) {
        return close(tenantId, psp, pspDisputeId, DisputeStage.LOST);
    }

    public Mono<DisputeEntity> closeCancelled(String tenantId, String psp, String pspDisputeId) {
        return close(tenantId, psp, pspDisputeId, DisputeStage.CANCELLED);
    }

    public Mono<DisputeEntity> closeClosed(String tenantId, String psp, String pspDisputeId) {
        return close(tenantId, psp, pspDisputeId, DisputeStage.CLOSED);
    }

    /**
     * Terminal close with stage outcome. Emits PAYMENT_CHARGEBACK_CLOSED.
     */
    public Mono<DisputeEntity> close(String tenantId, String psp, String pspDisputeId, DisputeStage outcome) {
        if (outcome == null) return Mono.error(new IllegalArgumentException("outcome must not be null"));
        if (!isTerminal(outcome)) return Mono.error(new IllegalArgumentException("Outcome must be terminal stage"));

        final String t = requireNonBlank(tenantId, "tenantId");
        final String provider = requireNonBlank(psp, "psp");
        final String providerDisputeId = requireNonBlank(pspDisputeId, "pspDisputeId");

        return tx.transactional(
                findByTenantAndProviderDispute(t, provider, providerDisputeId)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Dispute not found")))
                        .flatMap(d -> paymentRepo.findById(d.getPaymentId())
                                .switchIfEmpty(Mono.error(new IllegalStateException("Payment missing for dispute")))
                                .flatMap(pmt -> {
                                    d.setStage(outcome);
                                    d.setClosedAt(Instant.now());
                                    d.setUpdatedAt(Instant.now());
                                    return disputeRepo.save(d)
                                            .flatMap(saved -> emitClosed(pmt, saved).thenReturn(saved));
                                }))
        );
    }

    /* ======================================================================
       LOOKUP
       ====================================================================== */

    public Mono<DisputeEntity> findByTenantAndProviderDispute(String tenantId, String psp, String pspDisputeId) {
        final String t = requireNonBlank(tenantId, "tenantId");
        final String provider = requireNonBlank(psp, "psp");
        final String providerDisputeId = requireNonBlank(pspDisputeId, "pspDisputeId");

        // Use DatabaseClient for the composite unique look-up, then hydrate via repository
        return db.sql("""
                SELECT id FROM disputes
                WHERE tenant_id = :t AND psp = :p AND psp_dispute_id = :d
                LIMIT 1
                """)
                .bind("t", t)
                .bind("p", provider)
                .bind("d", providerDisputeId)
                .map((row, meta) -> row.get("id", Long.class))
                .one()
                .flatMap(disputeRepo::findById)
                .onErrorResume(e -> Mono.empty());
    }

    /* ======================================================================
       EVENTS
       ====================================================================== */

    private Mono<Void> emitOpened(PaymentEntity p, DisputeEntity d) {
        var evt = DisputeOpened.of(p, d);
        try {
            String json = om.writeValueAsString(evt);
            return outbox.saveEvent(
                            p.getTenantId(),
                            p.getSagaId(),
                            "payment",
                            p.getId(),
                            EVT_OPENED,
                            String.valueOf(p.getId()),
                            json,
                            Map.of("psp", d.getPsp(), "psp-dispute-id", d.getPspDisputeId()))
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private Mono<Void> emitClosed(PaymentEntity p, DisputeEntity d) {
        var evt = DisputeClosed.of(p, d);
        try {
            String json = om.writeValueAsString(evt);
            return outbox.saveEvent(
                            p.getTenantId(),
                            p.getSagaId(),
                            "payment",
                            p.getId(),
                            EVT_CLOSED,
                            String.valueOf(p.getId()),
                            json,
                            Map.of("psp", d.getPsp(), "psp-dispute-id", d.getPspDisputeId(), "outcome", d.getStage().name()))
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /* ======================================================================
       HELPERS
       ====================================================================== */

    private static boolean isTerminal(DisputeStage s) {
        return s == DisputeStage.WON || s == DisputeStage.LOST
                || s == DisputeStage.CLOSED || s == DisputeStage.CANCELLED;
    }

    private static String normalizeCurrency(String code) {
        final String cur = requireNonBlank(code, "currencyCode").toUpperCase();
        try { Currency.getInstance(cur); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ISO currency code: " + cur, e);
        }
        return cur;
    }

    private static long requireNonNegative(long v, String name) {
        if (v < 0) throw new IllegalArgumentException(name + " must be >= 0");
        return v;
    }

    private static String requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return v;
    }

    /* ======================================================================
       Event payloads (local records; keep them simple and stable)
       ====================================================================== */

    @Value
    private static class DisputeOpened {
        UUID sagaId;
        String tenantId;
        Long orderId;
        Long paymentId;
        Long disputeId;
        String psp;
        String pspDisputeId;
        String reasonCode;
        Long amountMinor;
        String currencyCode;
        Instant ts;

        static DisputeOpened of(PaymentEntity p, DisputeEntity d) {
            return new DisputeOpened(
                    p.getSagaId(),
                    p.getTenantId(),
                    p.getOrderId(),
                    p.getId(),
                    d.getId(),
                    d.getPsp(),
                    d.getPspDisputeId(),
                    d.getReasonCode(),
                    d.getAmountMinor(),
                    d.getCurrencyCode(),
                    Instant.now()
            );
        }
    }

    @Value
    private static class DisputeClosed {
        UUID sagaId;
        String tenantId;
        Long orderId;
        Long paymentId;
        Long disputeId;
        String outcome;        // WON/LOST/CLOSED/CANCELLED
        String psp;
        String pspDisputeId;
        Long amountMinor;
        String currencyCode;
        Instant ts;

        static DisputeClosed of(PaymentEntity p, DisputeEntity d) {
            return new DisputeClosed(
                    p.getSagaId(),
                    p.getTenantId(),
                    p.getOrderId(),
                    p.getId(),
                    d.getId(),
                    d.getStage().name(),
                    d.getPsp(),
                    d.getPspDisputeId(),
                    d.getAmountMinor(),
                    d.getCurrencyCode(),
                    Instant.now()
            );
        }
    }
}
