package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.HomeworkSubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row from the admin homework detail: a roster student plus
 * (optionally) the submission row recorded for them.
 *
 * <p>A null {@code status} means no submission row exists for this student
 * on this homework — the service layer synthesises this from the roster
 * join, not from an implicit NOT_STARTED row in the table.
 */
public record HomeworkSubmissionView(
        UUID studentUserId,
        String displayName,
        HomeworkSubmissionStatus status,
        String studentNote,
        Instant submittedAt,
        UUID markedBy,
        Instant markedAt
) {}
