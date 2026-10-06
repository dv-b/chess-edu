package com.chessedu.platform.identity.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The sender of a message. {@code id} is the stable Telegram user id we key the
 * {@code telegram_account} row on. The rest is the mutable profile snapshot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgUser(
        long id,
        @JsonProperty("is_bot") boolean isBot,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String username,
        @JsonProperty("language_code") String languageCode) {
}
