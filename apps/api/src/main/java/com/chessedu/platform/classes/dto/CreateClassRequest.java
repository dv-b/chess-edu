package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.ClassLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/v1/admin/classes. Description and level are optional;
 * the school is derived from the authenticated principal.
 */
public record CreateClassRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String description,
        ClassLevel level
) {}
