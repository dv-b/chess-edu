package com.chessedu.platform.classes.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Payload for ad-hoc (non-pattern) session creation. Times are UTC instants;
 * the UI composes them from the class's timezone + a wall-clock pick.
 * Server re-checks {@code endsAt > startsAt}.
 */
public record CreateSessionRequest(
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Size(max = 4000) String notes
) {}
