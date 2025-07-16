package com.github.dimitryivaniuta.shipping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Shipping Service microservice.
 * Schedules and tracks shipments once inventory is reserved.
 */
@Slf4j
@SpringBootApplication
public class ShippingServiceApplication {

    /**
     * Main method to launch the Shipping Service application.
     *
     * @param args command‑line arguments passed to the application
     */
    public static void main(final String[] args) {
        SpringApplication.run(ShippingServiceApplication.class, args);
        log.info("Shipping Service application started successfully.");
    }
}
