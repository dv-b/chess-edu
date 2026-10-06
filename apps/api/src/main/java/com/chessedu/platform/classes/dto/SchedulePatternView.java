package com.chessedu.platform.classes.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Outbound shape for a weekly schedule pattern. Day-of-week is emitted as
 * the ISO enum name (MONDAY, TUESDAY, …) so the UI doesn't have to carry
 * a 1–7 conversion table.
 */
public record SchedulePatternView(
        UUID id,
        UUID classId,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate startsOn,
        LocalDate endsOn,
        String timezone
) {}
