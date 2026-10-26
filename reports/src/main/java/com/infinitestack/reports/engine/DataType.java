package com.infinitestack.reports.engine;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The value types a Block port and a report input can carry (ARCHITECTURE section 12).
 * Values travel as plain JSON-like Java objects: String, Number, Boolean, Map and List.
 */
public enum DataType {
    STRING, NUMBER, BOOLEAN, DATE, DATETIME, OBJECT, ARRAY, GEOMETRY;

    /**
     * Checks a value against this type and normalizes what a form or a JSON body can send
     * ("12" for a number, "true" for a boolean). Dates stay ISO-8601 strings.
     *
     * @throws IllegalArgumentException with a readable reason when the value does not fit
     */
    public Object coerce(Object value) {
        if (value == null) return null;
        switch (this) {
            case STRING:
                if (value instanceof String) return value;
                if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
                break;
            case NUMBER:
                if (value instanceof Number) return value;
                if (value instanceof String s && !s.isBlank()) {
                    try {
                        return s.contains(".") || s.contains("e") || s.contains("E")
                                ? Double.valueOf(s.trim()) : Long.valueOf(s.trim());
                    } catch (NumberFormatException e) {
                        break;
                    }
                }
                break;
            case BOOLEAN:
                if (value instanceof Boolean) return value;
                if ("true".equalsIgnoreCase(String.valueOf(value))) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(String.valueOf(value))) return Boolean.FALSE;
                break;
            case DATE:
                if (value instanceof String s) {
                    try {
                        LocalDate.parse(s.trim());
                        return s.trim();
                    } catch (DateTimeParseException e) {
                        break;
                    }
                }
                break;
            case DATETIME:
                if (value instanceof String s) {
                    try {
                        OffsetDateTime.parse(s.trim());
                        return s.trim();
                    } catch (DateTimeParseException e) {
                        break;
                    }
                }
                break;
            case OBJECT:
                if (value instanceof Map) return value;
                break;
            case ARRAY:
                if (value instanceof List) return value;
                break;
            case GEOMETRY:
                // A GeoJSON geometry object: {"type": "...", "coordinates": ...}
                if (value instanceof Map<?, ?> m && m.get("type") instanceof String) return value;
                break;
        }
        throw new IllegalArgumentException("expected " + name().toLowerCase() + ", got " + describe(value));
    }

    /** Types whose values have inner fields a template may reach into ({{node.port.field}}). */
    public boolean isStructured() {
        return this == OBJECT || this == ARRAY || this == GEOMETRY;
    }

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static DataType fromJson(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown data type '" + value + "'");
        }
    }

    private static String describe(Object value) {
        String text = String.valueOf(value);
        if (text.length() > 40) text = text.substring(0, 40) + "...";
        return value.getClass().getSimpleName().toLowerCase() + " '" + text + "'";
    }
}
