package com.chessedu.platform.identity.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Telegram-bot knobs.
 *
 * <p>{@code enabled} is the master switch — when false (the default), the bot
 * worker bean isn't created at all, so normal dev boots, the test profile, and
 * CI never try to long-poll Telegram. Flip it on once a real bot token is set.
 *
 * <p>{@code apiBaseUrl} is overridable so an integration test can point the
 * client at a mock HTTP server instead of {@code api.telegram.org}.
 */
@ConfigurationProperties(prefix = "chessedu.telegram")
public record TelegramProperties(
        boolean enabled,
        String botToken,
        String botUsername,
        String apiBaseUrl,
        Integer pollTimeoutSeconds,
        Duration ottTtl) {

    public TelegramProperties {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            apiBaseUrl = "https://api.telegram.org";
        }
        if (pollTimeoutSeconds == null || pollTimeoutSeconds <= 0) {
            pollTimeoutSeconds = 50;
        }
        if (ottTtl == null) {
            ottTtl = Duration.ofMinutes(10);
        }
    }

    /** The {@code /bot<token>} path prefix for every Bot API call. */
    public String botApiPrefix() {
        return apiBaseUrl + "/bot" + botToken;
    }
}
