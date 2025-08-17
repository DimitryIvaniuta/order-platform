package com.github.dimitryivaniuta.orderservice.model.converter;

import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class OrderStatusWritingConverter implements Converter<OrderStatus, Short> {
    @Override
    public Short convert(OrderStatus source) { return (short) source.code(); }
}