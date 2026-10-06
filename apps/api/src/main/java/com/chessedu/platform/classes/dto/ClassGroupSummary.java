package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.ClassGroupStatus;
import com.chessedu.platform.classes.model.ClassLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * List-row projection. Counts are computed via a correlated subquery so we
 * don't have to eagerly fetch the whole roster for every row.
 */
public record ClassGroupSummary(
        UUID id,
        String name,
        ClassLevel level,
        ClassGroupStatus status,
        long teacherCount,
        long studentCount,
        Instant createdAt
) {}
