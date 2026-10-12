package com.chessedu.platform.identity.telegram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/telegram/accept} — the raw OTT from the deeplink. */
public record TelegramAcceptRequest(
        @NotBlank @Size(max = 200) String ott
) {
}
