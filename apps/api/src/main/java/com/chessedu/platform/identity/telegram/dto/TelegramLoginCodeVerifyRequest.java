package com.chessedu.platform.identity.telegram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/telegram/login-codes/{id}/verify}. */
public record TelegramLoginCodeVerifyRequest(
        @NotBlank @Size(min = 4, max = 12) String code
) {
}
