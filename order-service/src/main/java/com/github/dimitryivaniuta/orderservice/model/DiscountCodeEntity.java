package com.github.dimitryivaniuta.orderservice.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Table("discount_codes")
@Data
public class DiscountCodeEntity {
    @Id
    String code;
    DiscountType type;
    BigDecimal value;
    Boolean active;
    OffsetDateTime startsAt;
    OffsetDateTime expiresAt;
    Integer maxUses;
    Integer usedCount;

    public boolean isActiveNow() {
        var now = OffsetDateTime.now();
        return Boolean.TRUE.equals(active) && (startsAt.isBefore(now) || startsAt.isEqual(now)) && expiresAt.isAfter(now);
    }
}