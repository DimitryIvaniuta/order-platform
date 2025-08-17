package com.github.dimitryivaniuta.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.dimitryivaniuta.common.security.RequiredAudienceValidator;
import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.github.dimitryivaniuta.gateway.security.ResourceServerJwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Reactive security configuration.
 * Validates JWT via issuer discovery or JWK set, adds timestamp and audience validators,
 * uses a reactive JwtAuthenticationConverter, and enforces tenant-aware route rules.
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Audience, issuer and token parameters. */
    private final JwtProperties jwtProperties;

    /** Issuer and optional jwk-set-uri for resource-server validation. */
    private final ResourceServerJwtProperties resourceProps;

    // Provided by :common auto-config as a reactive converter (Jwt -> Mono<AbstractAuthenticationToken>)
    private final Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                            ReactiveJwtDecoder decoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(ex -> ex
                                .pathMatchers(
                                        "/actuator/**",
                                        "/.well-known/**",
//                                "/api/.well-known/**",
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**",
                                        "/auth/login"
                                ).permitAll()
                                .pathMatchers(HttpMethod.POST, "/orders/**").authenticated()
                                .pathMatchers(HttpMethod.GET,  "/orders/**").authenticated()
                                .pathMatchers(HttpMethod.PUT,  "/orders/**").authenticated()
                                .pathMatchers(HttpMethod.DELETE, "/orders/**").authenticated()

                                // everything else requires auth
                                .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .jwtDecoder(decoder)
                                .jwtAuthenticationConverter(jwtAuthConverter)
                        )
                )
                .build();
    }

    /**
     * Reactive JWT decoder built from issuer or JWK set and composed validators.
     * Validators:
     * - JwtTimestampValidator with 2 minutes skew
     * - RequiredAudienceValidator if audience is configured
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        // Choose JWKS if provided, otherwise OIDC discovery via issuer
        final NimbusReactiveJwtDecoder decoder =
                resourceProps.hasJwks()
                        ? NimbusReactiveJwtDecoder.withJwkSetUri(resourceProps.getJwkSetUri()).build()
                        : NimbusReactiveJwtDecoder.withIssuerLocation(resourceProps.getIssuerUri()).build();

        // validators
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(new JwtTimestampValidator(Duration.ofMinutes(2)));

        var allowedAudiences = parseAudiences(jwtProperties.getAudience());
        if (!allowedAudiences.isEmpty()) {
            validators.add(new RequiredAudienceValidator(allowedAudiences)); // from :common
        }

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("X-Correlation-ID"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /**
     * Parses a comma-separated audience string into a list.
     * Accepts single value or "aud1,aud2,aud3".
     */
    private static List<String> parseAudiences(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
