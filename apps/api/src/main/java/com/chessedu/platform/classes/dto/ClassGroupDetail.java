package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.ClassGroupStatus;
import com.chessedu.platform.classes.model.ClassLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full view for the detail page: class metadata + both rosters. Assembled
 * by the service from the {@code ClassGroup} entity and two roster queries.
 */
public record ClassGroupDetail(
        UUID id,
        UUID schoolId,
        String name,
        String description,
        ClassLevel level,
        ClassGroupStatus status,
        List<ClassRosterMember> teachers,
        List<ClassRosterMember> students,
        Instant createdAt,
        Instant updatedAt
) {}
