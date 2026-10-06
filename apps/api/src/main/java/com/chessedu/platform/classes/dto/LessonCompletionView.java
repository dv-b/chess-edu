package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.LessonCompletionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row from the admin lesson detail: a roster student plus
 * (optionally) the completion row recorded for them. Mirrors the shape
 * of {@link HomeworkSubmissionView} minus the teacher-facing fields
 * ({@code markedBy}, {@code studentNote}) — lessons are self-reported
 * only in v1.
 *
 * <p>A null {@code status} means no completion row exists for this
 * student on this lesson — the service layer synthesises that from the
 * roster join, not from an implicit NOT_STARTED row in the table.
 */
public record LessonCompletionView(
        UUID studentUserId,
        String displayName,
        LessonCompletionStatus status,
        Instant markedAt
) {}
