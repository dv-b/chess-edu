package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.LessonCompletionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full detail for a single lesson as seen by a student. Mirrors
 * {@link LessonDetail} / {@link StudentHomeworkDetail} but with a
 * single embedded completion object (the caller's own) rather than a
 * roster-wide sheet — students never see their classmates' reads.
 *
 * <p>Unlike {@link StudentHomeworkDetail}, resources are NOT scrubbed
 * server-side — MCQ {@code correctIndex} ships on the wire by design
 * (the client reveals it after the student locks in a guess; it's a
 * UX choice, not a security boundary, and lessons are revisitable
 * study material rather than gradable homework).
 *
 * <p>Class name is denormalised onto the detail so the viewer's
 * header doesn't need a second round-trip. Same for
 * {@code sessionStartsAt} when the lesson is anchored to a session.
 */
public record StudentLessonDetail(
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
        /** Ordered content blocks. No server-side stripping. */
        List<LessonResourceView> resources
) {}
