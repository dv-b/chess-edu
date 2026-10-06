package com.chessedu.platform.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Canonical error envelope. Every non-2xx response returning JSON is shaped
 * like this so the client has exactly one structure to parse.
 *
 * <pre>{@code
 * {
 *   "code": "VALIDATION_FAILED",
 *   "message": "Request validation failed",
 *   "timestamp": "2026-04-18T09:30:00Z",
 *   "path": "/api/v1/students",
 *   "requestId": "7d3c1f...",
 *   "fieldErrors": [{"field": "email", "code": "EMAIL_INVALID"}],
 *   "details": {"...": "..."}
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String path,
        String requestId,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    public record FieldError(String field, String code, String message) {
    }

    public static ApiError of(ErrorCode code, String path, String requestId) {
        return new ApiError(code.name(), code.defaultMessage(), Instant.now(), path, requestId, null, null);
    }

    public static ApiError of(ErrorCode code, String message, String path, String requestId) {
        return new ApiError(code.name(), message, Instant.now(), path, requestId, null, null);
    }

    public ApiError withFieldErrors(List<FieldError> errs) {
        return new ApiError(code, message, timestamp, path, requestId, errs, details);
    }

    public ApiError withDetails(Map<String, Object> d) {
        return new ApiError(code, message, timestamp, path, requestId, fieldErrors, d);
    }
}
