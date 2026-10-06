package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.HomeworkSubmissionStatus;
import jakarta.validation.constraints.Size;

/**
 * Student's self-update of their own submission on one homework.
 *
 * <p>A null {@code status} clears the existing row — consistent with how the
 * teacher/admin override (and attendance) handle a cleared mark. The service
 * rejects {@link HomeworkSubmissionStatus#NOT_STARTED} on this endpoint: if
 * the student wants to reset, they send a null status.
 */
public record UpdateMySubmissionRequest(
        HomeworkSubmissionStatus status,
        @Size(max = 2_000) String studentNote
) {}
