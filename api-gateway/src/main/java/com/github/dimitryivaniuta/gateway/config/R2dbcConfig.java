package com.github.dimitryivaniuta.gateway.config;

import com.github.dimitryivaniuta.gateway.model.converter.UserStatusReadingConverter;
import com.github.dimitryivaniuta.gateway.model.converter.UserStatusWritingConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import java.util.List;

@Configuration
@EnableR2dbcAuditing
public class R2dbcConfig {

    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions() {
        // pick the Postgres dialect (adjust if you use another)
        var dialect = PostgresDialect.INSTANCE;
        var storeConversions = CustomConversions.StoreConversions.of(dialect.getSimpleTypeHolder());

        List<Converter<?, ?>> converters = List.of(
                new UserStatusReadingConverter(),
                new UserStatusWritingConverter()
        );

        return new R2dbcCustomConversions(storeConversions, converters);
    }
}