package com.chessedu.platform.identity.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The chat a message belongs to. For a private DM with the bot, {@code id}
 * equals the sender's user id, but we store it separately on
 * {@code telegram_account.chat_id} so a future group/channel link doesn't
 * conflate the two.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgChat(
        long id,
        String type) {
}
