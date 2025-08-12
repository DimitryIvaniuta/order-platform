package com.github.dimitryivaniuta.gateway.model.converter;

import com.github.dimitryivaniuta.gateway.model.UserStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class UserStatusReadingConverter implements Converter<Short, UserStatus> {
    @Override
    public UserStatus convert(Short source) {
        return UserStatus.fromDbValue(source);
    }
}