package com.chessedu.platform.identity.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One Telegram update from {@code getUpdates}. We only care about message
 * updates for the bot signup flow; everything else (edited messages, callback
 * queries, …) is ignored — {@link #message()} is null for those and the worker
 * skips them after advancing the offset.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgUpdate(
        @JsonProperty("update_id") long updateId,
        TgMessage message) {
}
