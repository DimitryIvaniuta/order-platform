package com.github.dimitryivaniuta.common.security;

import java.util.Collection;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.*;
import reactor.core.publisher.Mono;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

/** Autoconfigures the reactive Jwt auth converter from our MultiTenantAuthoritiesConverter. */
@AutoConfiguration
@EnableConfigurationProperties({
        MultiTenantAuthzProperties.class,
        SecurityClaimsProperties.class
})
@ConditionalOnClass({ JwtAuthenticationConverter.class, ReactiveJwtAuthenticationConverterAdapter.class })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SecurityCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MultiTenantAuthoritiesConverter multiTenantAuthoritiesConverter(SecurityClaimsProperties props) {
        return new MultiTenantAuthoritiesConverter(props);
    }

    @Bean(name = "jwtAuthConverter")
    @ConditionalOnMissingBean(name = "jwtAuthConverter")
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter(
            MultiTenantAuthoritiesConverter authoritiesConverter) {

        var delegate = new JwtAuthenticationConverter();
        // plug in our authorities mapping
        @SuppressWarnings("unchecked")
        Converter<Jwt, Collection<GrantedAuthority>> conv =
                (Converter<Jwt, Collection<GrantedAuthority>>) (Converter<?, ?>) authoritiesConverter;
        delegate.setJwtGrantedAuthoritiesConverter(conv);

        // adapt servlet-style converter to reactive
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }
}
