package com.chessedu.platform.identity.telegram.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/auth/telegram/login-codes}. Returned
 * whether or not the identifier matched a Telegram-linked account — an
 * unmatched identifier gets a random id that no code will ever verify —
 * so the endpoint can't be used to probe which usernames exist.
 */
public record TelegramLoginCodeView(
        UUID id,
        Instant expiresAt
) {
}
