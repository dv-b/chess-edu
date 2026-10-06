package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.AttendanceStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row from the attendance sheet: a student in the roster plus (optionally)
 * the mark that's been recorded for them on this session.
 *
 * <p>When {@code status} is null the student appears in the roster but nobody
 * has marked them yet — the UI treats that as "Unmarked" rather than "Absent".
 */
public record AttendanceMarkView(
        UUID studentUserId,
        String displayName,
        AttendanceStatus status,
        String note,
        UUID markedBy,
        Instant markedAt
) {}
