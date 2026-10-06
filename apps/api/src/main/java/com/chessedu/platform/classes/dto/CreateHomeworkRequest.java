package com.chessedu.platform.classes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload to create a homework assignment in a class.
 *
 * <p>{@code sessionId} is optional — if provided it must reference a
 * session that belongs to the same class. {@code instructions} and
 * {@code dueAt} are both optional. {@code resources} is the ordered list
 * of chess content blocks; a missing / null / empty list means "no
 * attached content" (instructions-only homework, which is fine for v1).
 */
public record CreateHomeworkRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 8_000) String instructions,
        Instant dueAt,
        UUID sessionId,
        @Valid @Size(max = 20) List<HomeworkResourceInput> resources
) {}
