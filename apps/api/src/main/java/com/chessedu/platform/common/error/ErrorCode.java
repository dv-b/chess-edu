package com.chessedu.platform.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes surfaced to clients. The frontend translates these codes
 * into locale-specific messages — the backend only returns a short English
 * fallback for operator logs and for cases where the client cannot translate.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Not authorized"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Resource conflict"),
    GONE(HttpStatus.GONE, "Resource no longer available"),
    TENANT_MISSING(HttpStatus.BAD_REQUEST, "Tenant context missing"),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN, "Tenant mismatch"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
