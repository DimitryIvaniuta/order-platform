package com.github.dimitryivaniuta.orderservice.model.converter;

import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class OrderStatusWrite implements Converter<OrderStatus, Integer> {
    public Integer convert(OrderStatus source){ return source.code(); }
}