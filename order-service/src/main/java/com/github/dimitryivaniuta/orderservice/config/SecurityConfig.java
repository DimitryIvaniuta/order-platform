package com.github.dimitryivaniuta.orderservice.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.dimitryivaniuta.common.security.RequiredAudienceValidator; // <-- from :common
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProps jwtProps;

    // Provided by :common auto-config (Converter<Jwt, Mono<AbstractAuthenticationToken>>)
    private final Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter;

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(c -> c.configurationSource(corsSource()))
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/**").permitAll()
                        // your API lives under /api thanks to spring.webflux.base-path
                        .pathMatchers(HttpMethod.POST,   "/orders/**").hasAuthority("SCOPE_orders.write")
                        .pathMatchers(HttpMethod.PUT,    "/orders/**").hasAuthority("SCOPE_orders.write")
                        .pathMatchers(HttpMethod.DELETE, "/orders/**").hasAuthority("SCOPE_orders.write")
                        .pathMatchers(HttpMethod.GET,    "/orders/**")
                        .hasAnyAuthority("SCOPE_orders.read","SCOPE_orders.write")
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .jwtDecoder(decoder)
                        .jwtAuthenticationConverter(jwtAuthConverter)))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder() {
        // Prefer JWKS if configured, else issuer discovery
        NimbusReactiveJwtDecoder dec =
                hasText(jwtProps.getJwksUri())
                        ? NimbusReactiveJwtDecoder.withJwkSetUri(jwtProps.getJwksUri()).build()
                        : NimbusReactiveJwtDecoder.withIssuerLocation(jwtProps.getIssuer()).build();

        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(new JwtTimestampValidator(Duration.ofMinutes(2)));

        var audiences = parseList(jwtProps.getAudience());
        if (!audiences.isEmpty()) {
            validators.add(new RequiredAudienceValidator(audiences)); // from :common
        }

        dec.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return dec;
    }

    @Bean
    CorsConfigurationSource corsSource() {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("*"));
        cfg.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("X-Correlation-ID"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static List<String> parseList(String v) {
        if (!hasText(v)) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
