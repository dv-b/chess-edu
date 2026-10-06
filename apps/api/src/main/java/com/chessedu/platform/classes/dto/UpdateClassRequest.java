package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.ClassGroupStatus;
import com.chessedu.platform.classes.model.ClassLevel;
import jakarta.validation.constraints.Size;

/**
 * Payload for PATCH /api/v1/admin/classes/{id}. Every field is optional;
 * null means "leave untouched". Empty-string is NOT treated as null — callers
 * that want to clear description send null explicitly (or omit the field).
 */
public record UpdateClassRequest(
        @Size(max = 160) String name,
        @Size(max = 2000) String description,
        ClassLevel level,
        ClassGroupStatus status
) {}
