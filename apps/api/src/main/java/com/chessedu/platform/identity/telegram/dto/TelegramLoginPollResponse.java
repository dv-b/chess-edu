package com.chessedu.platform.identity.telegram.dto;

import com.chessedu.platform.identity.auth.dto.TokenResponse;

/**
 * {@code PENDING} while the bot hasn't confirmed yet ({@code tokens} null);
 * {@code COMPLETED} exactly once, carrying the session. Expired / consumed /
 * unknown requests are HTTP errors, not statuses.
 */
public record TelegramLoginPollResponse(
        String status,
        TokenResponse tokens
) {
    public static TelegramLoginPollResponse pending() {
        return new TelegramLoginPollResponse("PENDING", null);
    }

    public static TelegramLoginPollResponse completed(TokenResponse tokens) {
        return new TelegramLoginPollResponse("COMPLETED", tokens);
    }
}
