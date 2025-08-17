package com.github.dimitryivaniuta.orderservice.model.converter;

import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class OrderStatusReadingConverter implements Converter<Short, OrderStatus> {
    @Override
    public OrderStatus convert(Short source) { return OrderStatus.fromCode(source); }
}
