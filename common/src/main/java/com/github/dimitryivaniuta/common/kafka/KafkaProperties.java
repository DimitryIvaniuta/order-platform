package com.github.dimitryivaniuta.common.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

//import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "kafka")
public class KafkaProperties {

//    @NotBlank
    private String bootstrapServers = "localhost:9092";

//    @NotBlank
    private String clientId = "app";

//    @NotBlank
    private String groupId = "app-consumer";

    private Topics topics = new Topics();

    // ---- getters/setters ----
    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String v) { this.bootstrapServers = v; }
    public String getClientId() { return clientId; }
    public void setClientId(String v) { this.clientId = v; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { this.groupId = v; }
    public Topics getTopics() { return topics; }
    public void setTopics(Topics topics) { this.topics = topics; }

    // ---------- nested ----------
    public static class Topics {
        private Commands commands = new Commands();
        private Events events = new Events();

        public Commands getCommands() { return commands; }
        public void setCommands(Commands commands) { this.commands = commands; }
        public Events getEvents() { return events; }
        public void setEvents(Events events) { this.events = events; }
    }

    public static class Commands {
        /** Topic for creating orders (command). */
        @NotBlank
        private String orderCreate = "order.command.create.v1";

        public String getOrderCreate() { return orderCreate; }
        public void setOrderCreate(String orderCreate) { this.orderCreate = orderCreate; }
    }

    public static class Events {
        /** Domain events topics we consume/produce. */
        @NotBlank private String order     = "order.events.v1";
        @NotBlank private String payment   = "payment.events.v1";
        @NotBlank private String inventory = "inventory.events.v1";

        public String getOrder() { return order; }
        public void setOrder(String order) { this.order = order; }
        public String getPayment() { return payment; }
        public void setPayment(String payment) { this.payment = payment; }
        public String getInventory() { return inventory; }
        public void setInventory(String inventory) { this.inventory = inventory; }

        public List<String> all() { return List.of(order, payment, inventory); }
    }

    // Convenience
    public List<String> allEventTopics() { return topics.getEvents().all(); }
}
