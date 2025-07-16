package com.github.dimitryivaniuta.inventory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Inventory Service microservice.
 * Reserves stock and publishes reservation events.
 */
@Slf4j
@SpringBootApplication
public class InventoryServiceApplication {

    /**
     * Main method to launch the Inventory Service application.
     *
     * @param args command‑line arguments passed to the application
     */
    public static void main(final String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
        log.info("Inventory Service application started successfully.");
    }
}
