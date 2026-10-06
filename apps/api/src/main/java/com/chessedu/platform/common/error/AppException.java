package com.chessedu.platform.common.error;

import java.util.Map;

/**
 * Base runtime exception for domain-level failures. Always carries an
 * {@link ErrorCode} so the global handler can map to a response deterministically.
 */
public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public AppException(ErrorCode code, String message) {
        super(message);
        this.code = code;
        this.details = null;
    }

    public AppException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public AppException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = null;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static AppException notFound(String what) {
        return new AppException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static AppException conflict(String message) {
        return new AppException(ErrorCode.CONFLICT, message);
    }

    public static AppException forbidden(String message) {
        return new AppException(ErrorCode.FORBIDDEN, message);
    }
}
