package com.chessedu.platform.classes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Partial edit of an existing homework assignment. Any field left null is
 * treated as "leave alone" unless a matching {@code clearXxx} flag is set,
 * in which case the field is explicitly wiped.
 *
 * <p>This lets the client differentiate "PATCH dueAt" (set to a new time)
 * from "PATCH remove-dueAt" (wipe the field) without having to resort to
 * a null-sentinel — which is the usual workaround for records.
 *
 * <p>{@code resources} follows slightly different semantics: a {@code null}
 * list means "don't touch the attached content"; a present list (even
 * empty) means "replace the stored list with this one". This is the only
 * way to express "clear all resources" on the wire without yet another
 * {@code clearXxx} flag, and it matches how the admin editor behaves —
 * the editor always either leaves resources alone or sends the full new
 * ordered list.
 */
public record UpdateHomeworkRequest(
        @Size(min = 1, max = 200) String title,
        @Size(max = 8_000) String instructions,
        Instant dueAt,
        UUID sessionId,
        /** If true, {@link #sessionId} (even if null) means "clear the session". */
        Boolean clearSession,
        /** If true, {@link #dueAt} (even if null) means "clear the due date". */
        Boolean clearDueAt,
        @Valid @Size(max = 20) List<HomeworkResourceInput> resources
) {}
