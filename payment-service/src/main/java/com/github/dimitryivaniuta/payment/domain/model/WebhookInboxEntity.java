package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Raw webhook payload store with deduplication by (provider, event_id). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("webhook_inbox")
public class WebhookInboxEntity {

    @Id
    private Long id;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("event_id")
    private String eventId;

    @Column("payload")
    private byte[] payload;

    @Column("processed_at")
    private Instant processedAt;

    private String provider;

    private String signature;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}

