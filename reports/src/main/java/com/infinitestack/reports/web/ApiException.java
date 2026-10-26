package com.infinitestack.reports.web;

import java.util.List;

import org.springframework.http.HttpStatus;

/** An error the API answers with a status and a list of readable problems. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<String> details) {
        super(message);
        this.status = status;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() { return status; }
    public List<String> details() { return details; }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException conflict(String message, List<String> details) {
        return new ApiException(HttpStatus.CONFLICT, message, details);
    }

    public static ApiException invalid(String message, List<String> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, details);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
