package com.chessedu.platform.identity.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The payload of a "Share Contact" tap. {@code phoneNumber} is Telegram-verified
 * (not user-typed). {@code userId} is the Telegram id the contact belongs to —
 * we compare it against the sender's id to reject a user sharing someone else's
 * number.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgContact(
        @JsonProperty("phone_number") String phoneNumber,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("user_id") Long userId) {
}
