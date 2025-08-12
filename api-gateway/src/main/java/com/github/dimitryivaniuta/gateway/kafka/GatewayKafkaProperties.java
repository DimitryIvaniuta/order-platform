package com.github.dimitryivaniuta.gateway.kafka;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed Kafka settings for the API Gateway.
 *
 * <p>Bind these from <code>kafka.*</code> in application.yml. Example:
 * <pre>
 * kafka:
 *   bootstrap-servers: localhost:9092
 *   client-id: api-gateway
 *   group-id: api-gateway-consumer
 *   acks: all
 *   compression-type: lz4
 *   command-topic:
 *     order-create: order.command.create.v1
 *   event-topics:
 *     - order.events.v1
 *     - payment.events.v1
 *     - inventory.events.v1
 * </pre>
 * </p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "kafka")
public class GatewayKafkaProperties {

    /** Producer acks policy (e.g., "all", "1", "0"). */
    @NotBlank
    private String acks = "all";

    /** Kafka bootstrap servers list, e.g., "localhost:9092". */
    @NotBlank
    private String bootstrapServers;

    /** Logical producer client id for metrics / quotas. */
    @NotBlank
    private String clientId;

    /** Topic names for outbound commands. */
    private CommandTopic commandTopic = new CommandTopic();

    /** Producer compression (e.g., "lz4", "snappy", "zstd"). */
    @NotBlank
    private String compressionType = "lz4";

    /** Event topics to consume (Saga progression). */
    private List<String> eventTopics;

    /** Consumer group id. */
    @NotBlank
    private String groupId;

    /**
     * Command topic names holder.
     */
    @Data
    public static class CommandTopic {
        /** Topic for "Order Create" command. */
        @NotBlank
        private String orderCreate;
    }
}
