package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.HomeworkSubmissionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full detail for a single homework as seen by a student (or parent viewing
 * their child). Mirrors {@link HomeworkDetail} with two important diffs:
 *
 * <ul>
 *   <li>The submission surface is a single embedded object (the caller's own
 *       row) rather than a roster-wide list — students never see their
 *       classmates' marks.</li>
 *   <li>Each {@link HomeworkResourceView}'s payload is pre-scrubbed of
 *       teacher-only fields (specifically {@code solution.uci}) before it
 *       reaches the wire, so even an attacker inspecting the HTTP response
 *       can't lift the answers.</li>
 * </ul>
 *
 * <p>Class name is denormalised onto the detail so the student viewer's
 * header doesn't need a second round-trip.
 */
public record StudentHomeworkDetail(
        UUID id,
        UUID classId,
        String className,
        UUID sessionId,
        String title,
        String instructions,
        Instant dueAt,
        Instant createdAt,
        /** Caller's own submission status — null means unmarked. */
        HomeworkSubmissionStatus status,
        String studentNote,
        Instant submittedAt,
        Instant markedAt,
        /** Ordered content blocks. Solutions are stripped. */
        List<HomeworkResourceView> resources,
        /**
         * The caller's own live block-level attempts (one per block they've
         * submitted on so far). Absence of a row for a given block means
         * "Not attempted" per the design. Historical attempts aren't
         * included — students revisit the live row only.
         */
        List<BlockAttemptView> attempts
) {}
