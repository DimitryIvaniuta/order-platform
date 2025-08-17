package com.github.dimitryivaniuta.orderservice.model;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum OrderStatus {
    PENDING((short) 0),
    AWAITING_PAYMENT((short) 1),
    RESERVED((short) 2),
    PAID((short) 3),
    REJECTED((short) 4),
    CANCELLED((short) 5);

    private final short code;

    private static final Map<Short, OrderStatus> LOOKUP =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(OrderStatus::code, Function.identity()));

    OrderStatus(short code) {
        this.code = code;
    }

    /** Numeric code stored in DB (SMALLINT). */
    public short code() {
        return code;
    }

    /** Resolve from a SMALLINT value. Throws if unknown. */
    public static OrderStatus fromCode(short code) {
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
