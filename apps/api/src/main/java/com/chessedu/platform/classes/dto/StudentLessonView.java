package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.LessonCompletionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row on the student's own lesson list. Carries the lesson context
 * plus the caller's own completion state.
 *
 * <p>Mirrors {@link StudentHomeworkView} with three simplifications:
 * lessons have no {@code dueAt}; completion has no {@code studentNote}
 * or {@code submittedAt} (read = read); and there's no block-level
 * attempt roll-up because v1 lessons don't store per-block progress.
 *
 * <p>{@code status == null} means no completion row exists yet — the
 * client should render this as "not started" without treating it as
 * an explicit {@link LessonCompletionStatus#NOT_STARTED} toggle.
 *
 * <p>{@code sessionStartsAt} is denormalised from the anchored session
 * (if any) so list rows can show a "Session · 20 Apr 18:00" pill
 * without a second fetch — same trick as {@link LessonSummary}.
 */
public record StudentLessonView(
        UUID id,
        UUID classId,
        String className,
        UUID sessionId,
        Instant sessionStartsAt,
        String title,
        String instructions,
        Instant createdAt,
        /** Caller's own completion status — null means unmarked. */
        LessonCompletionStatus status,
        Instant markedAt,
        /** Total number of authored content blocks on the lesson. */
        int blockCount
) {}
