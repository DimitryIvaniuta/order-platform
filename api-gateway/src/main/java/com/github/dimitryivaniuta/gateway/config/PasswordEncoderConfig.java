package com.github.dimitryivaniuta.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Exposes a BCrypt password encoder bean.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Returns a BCrypt encoder with a reasonable strength for servers.
     * @return password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 12 is a good default; adjust if you need faster hashing locally.
        return new BCryptPasswordEncoder(12);
    }
}
