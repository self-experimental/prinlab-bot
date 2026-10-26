package ru.printlab.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Json {
    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String value, Class<T> type) {
        return mapper.readValue(value, type);
    }
}
