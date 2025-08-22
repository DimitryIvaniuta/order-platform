package com.github.dimitryivaniuta.orderservice.web.dto;

public record ChooseShippingRequest(String optionCode,
                                    java.math.BigDecimal weight,
                                    String country,
                                    String postalCode
) {
}
