package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.LessonCompletionStatus;

/**
 * Student's self-update of their own completion on one lesson.
 *
 * <p>A null {@code status} clears the existing row back to the implicit
 * "not started" state — consistent with how the homework endpoint
 * (and attendance) handle a cleared mark.
 *
 * <p>Unlike {@link UpdateMySubmissionRequest}, explicit
 * {@link LessonCompletionStatus#NOT_STARTED} is accepted here. See the
 * enum docstring for why: writing NOT_STARTED preserves a
 * "opened-but-didn't-finish" trail that null would erase. Sending
 * {@link LessonCompletionStatus#READ} stamps {@code markedAt}.
 */
public record UpdateMyCompletionRequest(
        LessonCompletionStatus status
) {}
