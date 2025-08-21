package com.github.dimitryivaniuta.orderservice.web.dto;

import java.util.Map;

public record UpdateCartItemRequest(
        Integer quantity,
        String color,
        Map<String,String> attributes
) {}