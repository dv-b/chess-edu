package com.chessedu.platform.identity.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Telegram message. For the signup flow we read {@code text} (commands like
 * {@code /start INV_…} and {@code /cancel}) and {@code contact} (the payload of
 * a "Share Contact" tap). {@code from} is the sender, {@code chat} is where to
 * reply.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgMessage(
        TgUser from,
        TgChat chat,
        String text,
        TgContact contact) {
}
