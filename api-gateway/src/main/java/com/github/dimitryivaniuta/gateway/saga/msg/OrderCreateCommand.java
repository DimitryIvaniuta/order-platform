package com.github.dimitryivaniuta.gateway.saga.msg;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.github.dimitryivaniuta.gateway.web.dto.OrderLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Command emitted by the API Gateway to start the "create order" Saga.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><b>sagaId</b> is the global correlation key (UUID; prefer UUIDv7) and is also the Kafka key.</li>
 *   <li>Message is immutable and compact (Java 21 record), safe to serialize as JSON.</li>
 *   <li>All record components are listed in <b>alphabetical order</b> to align with your checkstyle rule.</li>
 * </ul>
 */
@Slf4j
@Builder
public record OrderCreateCommand(
        /** Customer FK in the orders domain. */
        @NotNull Long customerId,
        /** Line items (SKU, quantity, unit price). Must be non-empty. */
        @NotEmpty List<OrderLine> lines,
        /** Saga correlation id (Kafka key). */
        @NotNull UUID sagaId,
        /** Tenant identifier for multi-tenant isolation. */
        @NotBlank String tenantId,
        /** Total monetary amount for the order. */
        @NotNull BigDecimal totalAmount,
        /** Requesting user (JWT "sub"). */
        @NotBlank String userId
) {
    /**
     * Compact canonical constructor with basic validation (fail-fast).
     */
/*    public OrderCreateCommand {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");

        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException("totalAmount must be >= 0");
        }
    }*/

}
