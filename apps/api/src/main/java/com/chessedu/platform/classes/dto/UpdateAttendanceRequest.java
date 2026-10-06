package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.AttendanceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Bulk upsert for an attendance sheet. Each entry targets one student.
 *
 * <p>Semantics:
 * <ul>
 *   <li>If {@code status} is non-null the service upserts the (session,
 *       student) row to that status and note.</li>
 *   <li>If {@code status} is null the service clears any existing mark for
 *       that student — i.e. sets them back to "unmarked".</li>
 * </ul>
 *
 * <p>Students not included in {@code entries} are untouched. That lets the
 * UI submit partial edits (one student changed their mind) without sending
 * the entire roster each time.
 */
public record UpdateAttendanceRequest(
        @NotNull @Size(min = 1, max = 200) @Valid List<Entry> entries
) {
    public record Entry(
            @NotNull UUID studentUserId,
            /** Null means "clear this student's mark". */
            AttendanceStatus status,
            @Size(max = 500) String note
    ) {}
}
