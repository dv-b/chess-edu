package com.chessedu.platform.classes.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Payload for creating a weekly schedule pattern on a class. The server
 * validates that {@code endTime > startTime} and that {@code endsOn},
 * if present, is ≥ {@code startsOn}; both checks also live in the
 * {@code class_schedule_pattern} CHECK constraints as a safety net.
 *
 * <p>Timezone defaults to {@code Asia/Tashkent} at the service layer if
 * omitted, so the client can skip it for the common case.
 */
public record CreatePatternRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull LocalDate startsOn,
        LocalDate endsOn,
        @Size(max = 64) String timezone
) {}
