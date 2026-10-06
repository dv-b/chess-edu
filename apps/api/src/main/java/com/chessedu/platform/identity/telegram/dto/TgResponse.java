package com.chessedu.platform.identity.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The envelope every Bot API method returns: {@code {"ok": true, "result": …}}
 * on success, or {@code {"ok": false, "error_code": …, "description": …}} on
 * failure. {@code <T>} is the {@code result} shape for the specific call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgResponse<T>(
        boolean ok,
        T result,
        Integer error_code,
        String description) {
}
