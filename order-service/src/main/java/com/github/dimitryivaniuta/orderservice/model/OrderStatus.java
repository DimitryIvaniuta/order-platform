package com.github.dimitryivaniuta.orderservice.model;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum OrderStatus {
    PENDING(0),
    AWAITING_PAYMENT(1),
    RESERVED(2),
    PAID(3),
    REJECTED(4),
    CANCELLED(5);

    private final int code;

    private static final Map<Integer, OrderStatus> LOOKUP =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(OrderStatus::code, Function.identity()));

    OrderStatus(int  code) {
        this.code = code;
    }

    /** Numeric code stored in DB (SMALLINT). */
    public int  code() {
        return code;
    }

    /** Resolve from a SMALLINT value. Throws if unknown. */
    public static OrderStatus fromCode(int code) {
        OrderStatus status = LOOKUP.get(code);
        if (status == null) {
            throw new IllegalArgumentException("Unknown OrderStatus code: " + code);
        }
        return status;
    }

    /** Convenience overload for any numeric type (Short, Integer, Long, etc.). */
    public static OrderStatus fromCode(Number code) {
        if (code == null) {
            throw new IllegalArgumentException("OrderStatus code must not be null");
        }
        return fromCode(code.shortValue());
    }
}
