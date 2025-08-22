package com.github.dimitryivaniuta.orderservice.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@RequiredArgsConstructor
@WritingConverter
public class MapToJsonbConverter  implements Converter<Map<String,String>, Json> {

    private final ObjectMapper om;
    @Override public Json convert(Map<String,String> source) {
        try { return source == null ? null : Json.of(om.writeValueAsString(source)); }
        catch (Exception e) { throw new IllegalArgumentException("Serialize attributes", e); }
    }

}
