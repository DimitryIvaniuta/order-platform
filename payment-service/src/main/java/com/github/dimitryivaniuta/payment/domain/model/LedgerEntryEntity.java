package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Double-entry ledger entry. Each atomic posting creates a debit and a credit entry
 * with the same {@code journalId}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("ledger_entries")
public class LedgerEntryEntity {

    @Id
    private Long id;

    @Column("account_code")
    private String accountCode;

    @Column("booking_date")
    private LocalDate bookingDate;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("credit_minor")
    private long creditMinor;

    @Column("currency_code")
    private String currencyCode;

    @Column("debit_minor")
    private long debitMinor;

    @Column("capture_id")
    private Long captureId;

    @Column("journal_id")
    private java.util.UUID journalId;

    @Column("payment_id")
    private Long paymentId;

    @Column("refund_id")
    private Long refundId;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("value_date")
    private LocalDate valueDate;
}
