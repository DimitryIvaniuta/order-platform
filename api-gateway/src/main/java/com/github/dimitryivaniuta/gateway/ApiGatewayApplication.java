package com.github.dimitryivaniuta.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the API Gateway microservice.
 * Bootstraps Spring WebFlux and Kafka integrations.
 */
@Slf4j
@SpringBootApplication
public class ApiGatewayApplication {

    /**
     * Main method to launch the API Gateway application.
     *
     * @param args command‑line arguments passed to the application
     */
    public static void main(final String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
        log.info("API Gateway application started successfully.");
    }
}
