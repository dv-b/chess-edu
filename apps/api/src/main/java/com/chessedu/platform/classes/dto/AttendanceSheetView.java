package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.ClassSessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full attendance sheet for a single session: minimal session metadata plus
 * one row per roster student. Ordering matches the roster query
 * ({@link com.chessedu.platform.classes.repo.ClassStudentRepository#findStudents(UUID)}).
 */
public record AttendanceSheetView(
        UUID sessionId,
        UUID classId,
        Instant startsAt,
        Instant endsAt,
        ClassSessionStatus sessionStatus,
        List<AttendanceMarkView> marks
) {}
