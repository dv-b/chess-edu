package com.chessedu.platform.classes.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact row used on the admin homework list for a class. Counts are
 * computed against the current class roster so the UI can render a
 * "3 of 8 done" pill without a second roundtrip.
 *
 * <p>Both {@code instructions} and {@code sessionStartsAt} are carried
 * on the summary (not just on {@link HomeworkDetail}) so the list can
 * render a session anchor pill and a one-line instructions preview
 * without N+1 lookups or a per-row detail fetch. {@code sessionStartsAt}
 * is denormalised from the linked {@link com.chessedu.platform.classes.model.ClassSession}
 * — null whenever {@code sessionId} is null or the linked session has
 * been deleted out from under us.
 */
public record HomeworkSummary(
        UUID id,
        UUID classId,
        UUID sessionId,
        /** Start of the linked session, if any. Denormalised for cheap row render. */
        Instant sessionStartsAt,
        String title,
        /** Short authoring instructions — may be null. Shown as a truncated preview. */
        String instructions,
        Instant dueAt,
        Instant createdAt,
        Instant archivedAt,
        /** Number of students on the current class roster. */
        int rosterSize,
        /** How many of those students have a DONE submission. */
        int doneCount,
        /** How many of those students have a SKIPPED submission. */
        int skippedCount
) {}
