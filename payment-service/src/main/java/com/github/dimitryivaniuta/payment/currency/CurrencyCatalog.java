package com.github.dimitryivaniuta.payment.currency;

import reactor.core.publisher.Mono;

public interface CurrencyCatalog {
    Mono<Integer> fractionDigits(String code); // e.g. "USD" -> 2
}