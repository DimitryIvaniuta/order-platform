package com.github.dimitryivaniuta.orderservice.service;

import com.github.dimitryivaniuta.orderservice.model.OrderItemEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

// com.github.dimitryivaniuta.orderservice.service.TotalsCalculator
@Component
public class TotalsCalculator {
    public record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal shipping, BigDecimal total) {}

    public Totals compute(List<OrderItemEntity> items,
                          @Nullable BigDecimal discountAmount,
                          @Nullable BigDecimal shippingFee) {

        BigDecimal subtotal = items.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal disc = discountAmount == null ? BigDecimal.ZERO : discountAmount.max(BigDecimal.ZERO);
        BigDecimal ship = shippingFee   == null ? BigDecimal.ZERO : shippingFee.max(BigDecimal.ZERO);

        BigDecimal total = subtotal.subtract(disc).add(ship);
        if (total.signum() < 0) total = BigDecimal.ZERO;

        return new Totals(subtotal, disc, ship, total);
    }
}
