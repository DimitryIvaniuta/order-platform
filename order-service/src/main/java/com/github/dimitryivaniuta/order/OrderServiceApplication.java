package com.github.dimitryivaniuta.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Order Service microservice.
 * Handles order intake and saga initiation.
 */
@Slf4j
@SpringBootApplication
public class OrderServiceApplication {

    /**
     * Main method to launch the Order Service application.
     *
     * @param args command‑line arguments passed to the application
     */
    public static void main(final String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
        log.info("Order Service application started successfully.");
    }
}
