package com.github.dimitryivaniuta.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Payment Service microservice.
 * Processes payments as part of the saga.
 */
@Slf4j
@SpringBootApplication
public class PaymentServiceApplication {

    /**
     * Main method to launch the Payment Service application.
     *
     * @param args command‑line arguments passed to the application
     */
    public static void main(final String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
        log.info("Payment Service application started successfully.");
    }
}
