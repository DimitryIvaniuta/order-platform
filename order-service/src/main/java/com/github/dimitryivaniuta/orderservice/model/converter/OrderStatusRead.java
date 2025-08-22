package com.github.dimitryivaniuta.orderservice.model.converter;

import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class OrderStatusRead implements Converter<Integer, OrderStatus> {
    public OrderStatus convert(Integer source){ return OrderStatus.fromCode(source); }
}