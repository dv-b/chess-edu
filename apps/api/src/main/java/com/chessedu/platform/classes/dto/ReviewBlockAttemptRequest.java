package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.BlockAttemptStatus;

/**
 * Teacher-side request body for
 * {@code POST /api/v1/admin/classes/{classId}/homework/{homeworkId}/attempts/{attemptId}/review}.
 *
 * <p>{@link #status} must be {@link BlockAttemptStatus#REVIEWED_OK} or
 * {@link BlockAttemptStatus#REVIEWED_NEEDS_WORK}; the service rejects any
 * other value. {@link #teacherNote} is optional freeform text — trimmed
 * and treated as empty-to-null by the service.
 */
public record ReviewBlockAttemptRequest(
        BlockAttemptStatus status,
        String teacherNote
) {}
