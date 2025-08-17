package com.github.dimitryivaniuta.orderservice.web.dto;

import java.math.BigDecimal;

public record CreateOrderRequest(BigDecimal totalAmount) {}