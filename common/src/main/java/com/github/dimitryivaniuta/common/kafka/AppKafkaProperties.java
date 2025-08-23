package com.github.dimitryivaniuta.common.kafka;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "kafka")
public class AppKafkaProperties {

    @NotBlank
    private String bootstrapServers; // localhost:9092

    @NotBlank
    private String clientId; // app

    @NotBlank
    private String groupId; // app-consumer

    private Topics topics = new Topics();
    private Producer producer = new Producer();
    private Consumer consumer = new Consumer();

    public List<String> allEventTopics() { return topics.getEvents().all(); }

    @Getter
    @Setter
    public static class Topics {
        private Commands commands = new Commands();
        private Events events = new Events();
    }

    @Getter
    @Setter
    public static class Commands {
        @NotBlank
        private String orderCreate = "order.command.create.v1";
    }

    @Getter
    @Setter
    public static class Events {
        @NotBlank private String order     = "order.events.v1";
        @NotBlank private String payment   = "payment.events.v1";
        @NotBlank private String inventory = "inventory.events.v1";

        public List<String> all() { return List.of(order, payment, inventory); }
    }

    @Getter
    @Setter
    public static class Producer {
        @NotBlank private String acks = "all";
        @NotBlank private String compressionType = "lz4";
        private int lingerMs = 5;
        private int batchSize = 32 * 1024;
        private int deliveryTimeoutMs = 120_000;
    }

    @Getter
    @Setter
    public static class Consumer {
        private boolean enableAutoCommit = false;
        @NotBlank private String autoOffsetReset = "earliest";
        private int maxPollRecords = 500;
        private int fetchMaxWaitMs = 250;
        /** Commit interval used by ReceiverOptions#commitInterval. */
        private Duration commitInterval = Duration.ofSeconds(2);
    }
}
