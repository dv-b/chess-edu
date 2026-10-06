package com.chessedu.platform.classes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Payload to create a lesson in a class.
 *
 * <p>{@code sessionId} is optional — if provided it must reference a
 * session that belongs to the same class. {@code instructions} is
 * optional. {@code resources} is the ordered list of content blocks;
 * a missing / null / empty list means "no attached content"
 * (instructions-only lesson, which is fine for v1).
 *
 * <p>Unlike {@link CreateHomeworkRequest}, there's no {@code dueAt} —
 * lessons are revisitable study material, not due-datable.
 */
public record CreateLessonRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 8_000) String instructions,
        UUID sessionId,
        @Valid @Size(max = 20) List<LessonResourceInput> resources
) {}
