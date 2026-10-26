package com.infinitestack.reports.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every API error leaves as {"error": "...", "details": [...]}. */
@RestControllerAdvice(basePackages = "com.infinitestack.reports.controller")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return body(e.status(), e.getMessage(), e.details());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "Malformed request body", List.of(String.valueOf(e.getMostSpecificCause().getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleState(IllegalStateException e) {
        log.warn("[reports] {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), List.of());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, List<String> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        out.put("details", details);
        return ResponseEntity.status(status).body(out);
    }
}
