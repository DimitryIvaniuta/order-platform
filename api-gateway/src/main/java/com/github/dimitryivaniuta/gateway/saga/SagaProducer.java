package com.github.dimitryivaniuta.gateway.saga;

//import com.example.gateway.kafka.GatewayKafkaProperties;
//import com.example.gateway.saga.msg.OrderCreateCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.github.dimitryivaniuta.gateway.kafka.GatewayKafkaProperties;
import com.github.dimitryivaniuta.gateway.saga.msg.OrderCreateCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.kafka.sender.SenderRecord;

/**
 * Reactive Kafka producer for Saga command messages.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Serialize command payloads (JSON) using the shared {@link ObjectMapper}.</li>
 *   <li>Publish commands to Kafka with a key equal to {@code sagaId} for per-saga ordering.</li>
 *   <li>Attach metadata headers: {@code correlation-id}, {@code tenant-id}, {@code user-id},
 *       and optional {@code idempotency-key}.</li>
 *   <li>Return a non-blocking {@link Mono} that completes when the record is handed to the Kafka sender.</li>
 * </ul>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>The {@code sagaId} is the global correlation key (UUID, ideally UUIDv7) and is used as the Kafka key.</li>
 *   <li>Sender is created lazily from injected {@link SenderOptions} and cached for reuse.</li>
 *   <li>No {@code @Value}; all configuration is bound via {@link GatewayKafkaProperties}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaProducer {

    /** JSON serializer configured at the application level (modules, dates, etc.). */
    private final ObjectMapper objectMapper;

    /** Typed Kafka properties (bootstrap servers, topics, client/group ids, etc.). */
    private final GatewayKafkaProperties props;

    /** Cached Reactor Kafka sender instance (created from {@link #senderOptions}). */
    private volatile KafkaSender<String, byte[]> sender;

    /** Factory options for creating the Reactor Kafka sender (non-blocking). */
    private final SenderOptions<String, byte[]> senderOptions;

    /**
     * Sends an Order Create command to Kafka.
     *
     * <p>Headers attached (when present):</p>
     * <ul>
     *   <li><b>correlation-id</b> – if absent, defaults to {@code sagaId}.</li>
     *   <li><b>tenant-id</b> – tenant context.</li>
     *   <li><b>user-id</b> – request initiator (JWT {@code sub}).</li>
     *   <li><b>idempotency-key</b> – optional client-supplied idempotency key.</li>
     * </ul>
     *
     * @param cmd     command payload (must contain a non-null {@code sagaId})
     * @param headers additional metadata headers (may be empty but not null)
     * @return completion {@link Mono} (errors propagate if serialization or send fails)
     */
    public Mono<Void> sendOrderCreate(final OrderCreateCommand cmd, final Map<String, String> headers) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(cmd.sagaId(), "cmd.sagaId must not be null");
        Objects.requireNonNull(headers, "headers must not be null");

        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(cmd))
                .flatMap(bytes -> {
                    final String topic = props.getCommandTopic().getOrderCreate();
                    final String key = cmd.sagaId().toString();
                    final ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);

                    // Required headers (with sensible defaults)
                    addHeader(record, "correlation-id", headers.getOrDefault("correlation-id", key));
                    addHeader(record, "tenant-id", headers.get("tenant-id"));
                    addHeader(record, "user-id", headers.get("user-id"));
                    // Optional idempotency key
                    addHeader(record, "idempotency-key", headers.get("idempotency-key"));

                    if (log.isDebugEnabled()) {
                        log.debug("Sending order-create command topic={} key={} tenant={} user={}",
                                topic, key, headers.get("tenant-id"), headers.get("user-id"));
                    }

                    return sender()
                            .send(Mono.just(SenderRecord.create(record, cmd.sagaId())))
                            .then();
                });
    }

    /**
     * Lazily creates (and caches) the Reactor Kafka sender.
     *
     * @return a cached {@link KafkaSender} instance
     */
    private KafkaSender<String, byte[]> sender() {
        KafkaSender<String, byte[]> s = sender;
        if (s == null) {
            synchronized (this) {
                if (sender == null) {
                    sender = KafkaSender.create(senderOptions);
                    log.info("Initialized Reactor KafkaSender for clientId={}", props.getClientId());
                }
                s = sender;
            }
        }
        return s;
    }

    /**
     * Adds a header to a Kafka {@link ProducerRecord} if the value is non-null and non-blank.
     *
     * @param record the record to mutate
     * @param name   header name
     * @param value  header value
     */
    private void addHeader(final ProducerRecord<String, byte[]> record, final String name, final String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
