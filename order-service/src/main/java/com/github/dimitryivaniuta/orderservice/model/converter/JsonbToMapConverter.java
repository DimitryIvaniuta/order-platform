package com.github.dimitryivaniuta.orderservice.model.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@RequiredArgsConstructor
@ReadingConverter
public class JsonbToMapConverter implements Converter<Json, Map<String,String>> {

    private final ObjectMapper om;

    private static final TypeReference<Map<String,String>> TYPE = new TypeReference<>() {};
    @Override public Map<String,String> convert(Json source) {
        try {
            return om.readValue(source.asString(), TYPE);
        }
        catch (Exception e) { throw new IllegalArgumentException("Deserialize attributes", e); }
    }

}
