package com.github.dimitryivaniuta.payment.currency;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class CurrencyCatalogDb implements CurrencyCatalog {
    private final DatabaseClient db;

    @Override
    public Mono<Integer> fractionDigits(String code) {
        return db.sql("SELECT fraction_digits FROM currencies WHERE code = :c")
                .bind("c", code)
                .map((r, m) -> r.get(0, Integer.class))
                .one();
    }
}
