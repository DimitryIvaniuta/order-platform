package com.github.dimitryivaniuta.payment.api.dto;

import lombok.Value;

@Value
public class Amount {
    String currency;
    Long value;
}