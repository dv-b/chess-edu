package com.chessedu.platform.classes.dto;

import java.util.UUID;

/**
 * Compact view of a teacher or student in a class roster. Matches the
 * columns projected from {@link com.chessedu.platform.classes.repo.ClassTeacherRepository}
 * and {@link com.chessedu.platform.classes.repo.ClassStudentRepository}.
 */
public record ClassRosterMember(
        UUID userId,
        String email,
        String displayName
) {}
