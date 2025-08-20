package com.github.dimitryivaniuta.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;

import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider;

@Slf4j
@RequiredArgsConstructor
public class R2dbcVersionLogger {

    private final ConnectionFactory connectionFactory;

    @PostConstruct
    void logR2dbcVersions() {
        // What Spring actually wired (often a pooled CF)
        log.info("R2DBC ConnectionFactory class = {}", connectionFactory.getClass().getName());

        ConnectionFactoryMetadata md = connectionFactory.getMetadata();
        log.info("R2DBC ConnectionFactory name  = {}", md.getName());

        // SPI version (should be 1.0.0.RELEASE with Spring Boot 3.5.x)
        String spiVer = ConnectionFactoryMetadata.class.getPackage().getImplementationVersion();
        log.info("R2DBC SPI impl version       = {}", spiVer);

        // Postgres driver version (pulled from the driver package)
        String pgVer = PostgresqlConnectionFactoryProvider.class.getPackage().getImplementationVersion();
        log.info("r2dbc-postgresql impl version= {}", pgVer);
    }
}
