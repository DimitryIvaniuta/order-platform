package com.github.dimitryivaniuta.gateway.config;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.github.dimitryivaniuta.gateway.security.ResourceServerJwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive WebFlux security configuration:
 * <ul>
 *   <li>JWT validation via issuer discovery (JWKS key rotation handled automatically)</li>
 *   <li>Issuer, timestamp (with clock skew), and audience validation</li>
 *   <li>Authority mapping from scopes and Keycloak roles</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProperties jwtProperties;

    private final ResourceServerJwtProperties rsJwt;

    /**
     * Builds the reactive security filter chain for the API Gateway.
     *
     * @param http              reactive HTTP security
     * @param jwtDecoder        reactive JWT decoder (issuer discovery + validators)
     * @param jwtAuthConverter  converter mapping {@link Jwt} to {@link AbstractAuthenticationToken}
     * @return the configured {@link SecurityWebFilterChain}
     */
    @Bean
    public SecurityWebFilterChain securityFilterChain(
            final ServerHttpSecurity http,
            final ReactiveJwtDecoder jwtDecoder,
            final Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthConverter)
                        )
                )
                .build();
    }

    /**
     * Creates a {@link ReactiveJwtDecoder} from the OpenID Connect issuer.
     * <p>Validators applied:</p>
     * <ul>
     *   <li>Default + issuer validation ({@link JwtValidators#createDefaultWithIssuer(String)})</li>
     *   <li>Timestamp validation with 60s clock skew ({@link JwtTimestampValidator})</li>
     *   <li>Audience validation ({@link RequiredAudienceValidator})</li>
     * </ul>
     *
     * @param issuer the OIDC issuer URI (e.g. {@code https://auth.example.com/realms/order-platform})
     * @return configured {@link ReactiveJwtDecoder}
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") final String issuer) {

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator(Duration.ofSeconds(60));
        OAuth2TokenValidator<Jwt> withAudience =
                new RequiredAudienceValidator(List.of("order-platform-gateway")); // TODO: set your accepted audience(s)

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withTimestamp, withAudience));
        return decoder;
    }

    /**
     * Reactive converter that maps scopes and Keycloak roles to authorities.
     * <ul>
     *   <li>Scopes claim ({@code scope}/{@code scp}) → {@code SCOPE_*}</li>
     *   <li>Keycloak {@code realm_access.roles} and {@code resource_access.*.roles} → {@code ROLE_*}</li>
     * </ul>
     *
     * @return a converter producing {@link JwtAuthenticationToken}
     */
    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter() {
        return jwt -> {
            Set<GrantedAuthority> authorities = new HashSet<>();

            // 1) scopes → SCOPE_*
            JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
            scopeConverter.setAuthorityPrefix("SCOPE_");
            scopeConverter.setAuthoritiesClaimName("scope"); // falls back to 'scp' if needed
            authorities.addAll(scopeConverter.convert(jwt));

            // 2) Keycloak roles → ROLE_*
            authorities.addAll(KeycloakAuthoritiesExtractor.extract(jwt));

            return Mono.just(new JwtAuthenticationToken(jwt, authorities));
        };
    }
}
