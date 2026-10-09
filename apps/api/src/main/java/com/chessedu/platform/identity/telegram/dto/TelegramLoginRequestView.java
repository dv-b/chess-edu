package com.chessedu.platform.identity.telegram.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/auth/telegram/login-requests}. The web app
 * keeps {@code id} + {@code clientSecret} in an HttpOnly cookie and shows the
 * user {@code deeplink}. The nonce itself is only exposed inside the deeplink.
 */
public record TelegramLoginRequestView(
        UUID id,
        String clientSecret,
        String deeplink,
        Instant expiresAt
) {
}
