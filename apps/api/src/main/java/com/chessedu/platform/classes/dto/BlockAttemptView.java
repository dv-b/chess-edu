package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.BlockAttemptStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire view of one {@link com.chessedu.platform.classes.model.HomeworkBlockAttempt}
 * row. Both the student and the teacher see the same shape — the caller-side
 * filtering (only your own attempts for students; everyone's attempts for
 * teachers) happens in the service layer, not by hiding fields here.
 *
 * <p>{@code replacedById} is included so the admin audit view can rebuild a
 * history chain without an extra fetch. For live-only calls it's always
 * {@code null}.
 *
 * <p>{@code solutionRevealedAt} is intentionally exposed alongside
 * {@link #status status}: the frontend derives the "Solution revealed" pill
 * from {@code status == REVIEWED_NEEDS_WORK && solutionRevealedAt != null}
 * (see {@code docs/design/student-solution-moves.md}).
 */
public record BlockAttemptView(
        UUID id,
        UUID homeworkId,
        UUID studentUserId,
        int blockIndex,
        /** UCI moves the student submitted, always a JSON array. */
        JsonNode moves,
        BlockAttemptStatus status,
        String teacherNote,
        UUID reviewedBy,
        Instant reviewedAt,
        Instant solutionRevealedAt,
        /** Null on live rows; set on historical rows once superseded. */
        UUID replacedById,
        Instant createdAt,
        Instant updatedAt
) {}
