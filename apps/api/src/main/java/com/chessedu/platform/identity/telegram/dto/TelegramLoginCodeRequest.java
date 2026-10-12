package com.chessedu.platform.identity.telegram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/telegram/login-codes}: username, email or E.164 phone. */
public record TelegramLoginCodeRequest(
        @NotBlank @Size(max = 240) String identifier
) {
}
