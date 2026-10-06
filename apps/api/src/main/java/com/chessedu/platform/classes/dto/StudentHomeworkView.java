package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.HomeworkSubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row on the student's own homework list (also reused for the parent
 * rollup of a child's homework). Carries the assignment context plus the
 * single student's own submission state.
 *
 * <p>{@code status == null} means no submission row exists yet — the client
 * should render this as "not started" without treating it as an explicit
 * {@link HomeworkSubmissionStatus#NOT_STARTED} toggle.
 *
 * <p>The trailing {@code block*} counts are a server-side rollup of the
 * caller's live block-level attempts (see {@code HomeworkBlockAttempt}).
 * They let the list page surface teacher feedback ("Needs work") without
 * an extra per-row fetch. Counts are strictly ≥ 0 and each block
 * contributes to exactly one of the four "blocks*" counters based on its
 * derived state (see {@link HomeworkSubmissionStatus} for the homework-
 * level status, which is orthogonal — a student can self-mark a homework
 * DONE while still having {@code blocksNeedsWork > 0}).
 */
public record StudentHomeworkView(
        UUID id,
        UUID classId,
        String className,
        UUID sessionId,
        String title,
        String instructions,
        Instant dueAt,
        Instant createdAt,
        HomeworkSubmissionStatus status,
        String studentNote,
        Instant submittedAt,
        UUID markedBy,
        Instant markedAt,
        /** Total number of authored blocks on the homework (BOARD + future kinds). */
        int blockCount,
        /** Live attempts in SUBMITTED state — student submitted, teacher hasn't reviewed yet. */
        int blocksAwaitingReview,
        /** Live attempts in REVIEWED_OK state. */
        int blocksReviewedOk,
        /** Live attempts in REVIEWED_NEEDS_WORK with no {@code solutionRevealedAt}. */
        int blocksNeedsWork,
        /** Live attempts in REVIEWED_NEEDS_WORK that have had the authored solution revealed. */
        int blocksSolutionRevealed
) {}
