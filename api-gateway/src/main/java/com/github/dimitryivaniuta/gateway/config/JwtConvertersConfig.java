package com.github.dimitryivaniuta.gateway.config;

import com.github.dimitryivaniuta.gateway.security.MultiTenantAuthoritiesConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;

/**
 * Adapter to use Spring’s JwtAuthenticationConverter in reactive apps.
 */
@Configuration
@RequiredArgsConstructor
public class JwtConvertersConfig {

    private final MultiTenantAuthoritiesConverter authoritiesConverter;

    /**
     * Reactive adapter that builds JwtAuthenticationToken using our custom authorities.
     */
    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>>
    reactiveJwtAuthConverter() {

        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setPrincipalClaimName("sub");              // or "preferred_username"
        delegate.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }
}
