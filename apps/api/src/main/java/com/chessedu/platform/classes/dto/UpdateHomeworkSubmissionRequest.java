package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.HomeworkSubmissionStatus;
import jakarta.validation.constraints.Size;

/**
 * Teacher / admin override for a single student's submission state.
 *
 * <p>A null {@code status} clears any existing submission row, returning
 * the student to the implicit "not started" state (consistent with how
 * attendance handles a cleared mark).
 */
public record UpdateHomeworkSubmissionRequest(
        HomeworkSubmissionStatus status,
        @Size(max = 2_000) String studentNote
) {}
