package com.infinitestack.reports.repository;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSONB columns travel as text; this keeps the (de)serialization in one place. */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize value: " + e.getOriginalMessage(), e);
        }
    }

    public <T> T read(String text, Class<T> type) {
        if (text == null) return null;
        try {
            return mapper.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON in the database: " + e.getOriginalMessage(), e);
        }
    }

    public <T> T read(String text, TypeReference<T> type) {
        if (text == null) return null;
        try {
            return mapper.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON in the database: " + e.getOriginalMessage(), e);
        }
    }
}
