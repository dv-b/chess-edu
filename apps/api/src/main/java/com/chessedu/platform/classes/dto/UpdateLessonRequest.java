package com.chessedu.platform.classes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Partial edit of an existing lesson. Any field left null is treated as
 * "leave alone" unless a matching {@code clearXxx} flag is set, in
 * which case the field is explicitly wiped.
 *
 * <p>Mirrors {@link UpdateHomeworkRequest} minus the {@code dueAt} /
 * {@code clearDueAt} pair — lessons have no due date.
 *
 * <p>{@code resources} follows slightly different semantics: a
 * {@code null} list means "don't touch the attached content"; a
 * present list (even empty) means "replace the stored list with this
 * one". This is the only way to express "clear all resources" on the
 * wire without yet another {@code clearXxx} flag, and it matches how
 * the admin editor behaves — the editor always either leaves resources
 * alone or sends the full new ordered list.
 */
public record UpdateLessonRequest(
        @Size(min = 1, max = 200) String title,
        @Size(max = 8_000) String instructions,
        UUID sessionId,
        /** If true, {@link #sessionId} (even if null) means "clear the session". */
        Boolean clearSession,
        @Valid @Size(max = 20) List<LessonResourceInput> resources
) {}
