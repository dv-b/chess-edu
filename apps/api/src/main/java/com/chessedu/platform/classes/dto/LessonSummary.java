package com.chessedu.platform.classes.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact row used on the admin lesson list for a class. Counts are
 * computed against the current roster so the UI can render a
 * "5 of 8 read" pill without a second roundtrip.
 *
 * <p>Both {@code instructions} and {@code sessionStartsAt} are carried
 * on the summary (not just on {@link LessonDetail}) so the list can
 * render a session anchor pill and a one-line instructions preview
 * without N+1 lookups or a per-row detail fetch. {@code sessionStartsAt}
 * is denormalised from the linked
 * {@link com.chessedu.platform.classes.model.ClassSession} — null
 * whenever {@code sessionId} is null or the linked session has been
 * deleted out from under us.
 *
 * <p>Unlike {@link HomeworkSummary} there's no {@code dueAt} — study
 * material is revisitable, not due-datable — and the only "completion"
 * counter is {@code readCount} (lessons only distinguish
 * NOT_STARTED / READ).
 */
public record LessonSummary(
        UUID id,
        UUID classId,
        UUID sessionId,
        /** Start of the linked session, if any. Denormalised for cheap row render. */
        Instant sessionStartsAt,
        String title,
        /** Short authoring instructions — may be null. Shown as a truncated preview. */
        String instructions,
        Instant createdAt,
        Instant archivedAt,
        /** Number of students on the current class roster. */
        int rosterSize,
        /** How many of those students have marked READ. */
        int readCount,
        /** Number of authored content blocks on this lesson. */
        int blockCount
) {}
