package com.github.dimitryivaniuta.orderservice.model.converter;

import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class OrderStatusRead implements Converter<Short , OrderStatus> {
    public OrderStatus convert(Short  source){ return OrderStatus.fromCode(source); }
}