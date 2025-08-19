package com.github.dimitryivaniuta.orderservice.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Reactive Outbox publisher tunables.
 *
 * YAML prefix: {@code outbox.publisher}
 *
 * Example:
 * <pre>
 * outbox:
 *   publisher:
 *     events-topic: order.events.v1
 *     batch-size: 100
 *     poll-interval: 500ms
 *     lease-duration: 15s
 *     base-backoff: 2s
 *     max-backoff: 2m
 *     tenants: []           # optional, empty = dynamic discovery
 *     max-concurrent-tenants: 2
 * </pre>
 */
@Getter
@Setter
@ToString
@Validated
@ConfigurationProperties(prefix = "outbox.publisher")
public class OutboxPublisherProperties {

    /** Kafka topic for domain events emitted from the outbox. */
    @NotBlank
    private String eventsTopic = "order.events.v1";

    /** Max rows to claim & publish per poll tick. */
    @Min(1)
    private int batchSize = 100;

    /** How often the publisher wakes up to look for work. */
    @NotNull
    private Duration pollInterval = Duration.ofMillis(500);

    /** Lease duration applied when a batch is claimed. */
    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(15);

    /** Initial backoff used when a send fails (exponential strategy). */
    @NotNull
    private Duration baseBackoff = Duration.ofSeconds(2);

    /** Maximum backoff cap for retries. */
    @NotNull
    private Duration maxBackoff = Duration.ofMinutes(2);

    /**
     * Optional fixed tenant list. If empty, the publisher discovers tenants dynamically
     * based on rows with missing/expired leases.
     */
    @NotNull
    private List<String> tenants = new ArrayList<>();

    /** Parallelism when processing multiple tenants per tick. */
    @Min(1)
    private int maxConcurrentTenants = 2;

    /** Convenience: true if a static tenant list is configured. */
    public boolean hasStaticTenants() {
        return tenants != null && !tenants.isEmpty();
    }
}
