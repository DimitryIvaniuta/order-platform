package com.github.dimitryivaniuta.orderservice.outbox;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the reactive outbox publisher.
 */
@Data
@ConfigurationProperties(prefix = "outbox.publisher")
public class OutboxPublisherProperties {

    /** Maximum rows to claim per polling cycle. */
    private int batchSize = 100;

    /** Base backoff used in exponential strategy when a batch fails. */
    private Duration baseBackoff = Duration.ofSeconds(5);

    /** Maximum backoff cap. */
    private Duration maxBackoff = Duration.ofMinutes(2);

    /** How often to poll for new rows. */
    private Duration pollInterval = Duration.ofMillis(500);

    /** Lease duration placed on claimed rows to prevent duplicate workers. */
    private Duration leaseDuration = Duration.ofSeconds(30);

    /** Maximum attempts before you alert or dead-letter (still retried with maxBackoff). */
    private int maxAttempts = 20;

    /** Events topic to publish to. Override if you route per aggregate_type. */
    private String eventsTopic = "order.events.v1";
}
