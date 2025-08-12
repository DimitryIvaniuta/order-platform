package com.github.dimitryivaniuta.gateway.web;

import com.github.dimitryivaniuta.gateway.dto.CreateOrderRequest;
import com.github.dimitryivaniuta.gateway.saga.OrderSagaGatewayFacade;
import com.github.dimitryivaniuta.gateway.saga.SagaEventBus;
import com.github.dimitryivaniuta.gateway.saga.SagaStatusEntity;
import com.github.dimitryivaniuta.gateway.saga.SagaStatusRepository;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive edge controller that:
 * <ul>
 *   <li>Starts the <b>Order Create</b> Saga by emitting a Kafka command and seeding saga status;</li>
 *   <li>Exposes <b>polling</b> and <b>Server-Sent Events (SSE)</b> endpoints to observe saga progress;</li>
 *   <li>Operates fully with WebFlux (no servlet APIs).</li>
 * </ul>
 * <p>Security: requests are authenticated via JWT (validated by the gateway) and
 * multi-tenant authorization is enforced by route policies configured in {@code SecurityConfig}.</p>
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
public class OrderGatewayController {

    /** In-memory fan-out bus that streams per-saga updates to clients via SSE. */
    private final SagaEventBus bus;

    /** Orchestration facade that seeds saga_status and publishes Kafka commands. */
    private final OrderSagaGatewayFacade facade;

    /** Reactive repository to read current saga state for polling and SSE warm-up. */
    private final SagaStatusRepository statusRepo;

    /**
     * Starts an Order Create Saga for the given tenant.
     * <p>
     * Flow: validate + compute total → seed {@code saga_status} (STARTED) → send Kafka command →
     * return {@code sagaId}. The client can poll {@code /sagas/{id}} or open SSE at
     * {@code /sagas/{id}/stream} to follow the progress.
     *
     * @param tenant tenant identifier from the path (authorizer enforces TENANT_{tenant}:ORDER_WRITE)
     * @param req    order create request body (validated)
     * @param jwt    authenticated principal (JWT); {@code sub} used as userId
     * @param idemKey optional idempotency key to prevent duplicate submissions
     * @param corrId  optional correlation id propagated across logs and Kafka
     * @return mono with JSON payload containing {@code sagaId} and a relative {@code statusUrl}
     */
    @PostMapping(path = "/t/{tenant}/orders", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> createOrder(
            @PathVariable("tenant") final String tenant,
            @Valid @RequestBody final CreateOrderRequest req,
            @AuthenticationPrincipal final Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) final String idemKey,
            @RequestHeader(name = "X-Correlation-ID", required = false) final String corrId) {

        final String userId = jwt.getSubject();

        // Prepare common headers for Kafka (correlation, idempotency, tenant, user)
        final Map<String, String> headers = Map.of(
                "tenant-id", tenant,
                "user-id", userId,
                "correlation-id", corrId == null ? "" : corrId,
                "idempotency-key", idemKey == null ? "" : idemKey
        );

        // Compute total amount from line items: sum(quantity * unitPrice)
        final BigDecimal total = req.getLines().stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Start the saga via the facade (seed status → send command)
        return facade.startOrderCreate(tenant, userId, req.getCustomerId(), req.getLines(), total, headers)
                .map(id -> Map.of("sagaId", id.toString(),
                        "statusUrl", "/sagas/" + id));
    }

    /**
     * Returns the current persisted status of a saga (polling).
     *
     * @param id saga identifier (UUID/UUIDv7)
     * @return mono with {@link SagaStatusEntity} or empty if not found
     */
    @GetMapping(path = "/sagas/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SagaStatusEntity> sagaStatus(@PathVariable("id") final UUID id) {
        return statusRepo.findById(id);
    }

    /**
     * Streams live saga updates using Server-Sent Events (SSE).
     * <p>Starts by emitting the current persisted state (if any), then continues with new updates.</p>
     *
     * @param id saga identifier (UUID/UUIDv7)
     * @return an SSE stream of {@link SagaStatusEntity} updates
     */
    @GetMapping(path = "/sagas/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<SagaStatusEntity> sagaStream(@PathVariable("id") final UUID id) {
        return bus.stream(id).startWith(statusRepo.findById(id));
    }

}
