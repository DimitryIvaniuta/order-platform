package com.github.dimitryivaniuta.gateway.saga.msg;

import java.util.UUID;

/**
 * Base interface for Saga domain events emitted by downstream services and consumed by the gateway.
 *
 * <p>Events are immutable and must include at least:</p>
 * <ul>
 *   <li>{@link #sagaId()} — global correlation id (same as the command's {@code sagaId}).</li>
 *   <li>{@link #type()}   — event type code (e.g., {@code ORDER_CREATED}, {@code INVENTORY_RESERVED},
 *       {@code PAYMENT_CAPTURED}, {@code ORDER_COMPLETED}, {@code ORDER_FAILED}).</li>
 *   <li>{@link #reason()} — optional human-readable reason for failures/compensations (nullable).</li>
 * </ul>
 *
 * <p>Note: the gateway can deserialize to a generic JSON tree and only read these fields,
 * so producers are free to add extra fields without breaking the consumer.</p>
 */
public interface OrderEvent {

    /** Correlation id that ties this event to a specific saga. */
    UUID sagaId();

    /** Event type code describing the state transition. */
    String type();

    /** Optional failure/compensation reason; {@code null} when not applicable. */
    String reason();
}
