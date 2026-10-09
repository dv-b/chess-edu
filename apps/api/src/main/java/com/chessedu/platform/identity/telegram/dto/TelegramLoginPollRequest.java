package com.chessedu.platform.identity.telegram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/telegram/login-requests/{id}/poll}. */
public record TelegramLoginPollRequest(
        @NotBlank @Size(max = 200) String clientSecret
) {
}
