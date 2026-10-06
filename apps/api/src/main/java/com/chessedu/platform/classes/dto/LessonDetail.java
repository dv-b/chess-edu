package com.chessedu.platform.classes.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full admin detail for one lesson: the authored content plus one
 * {@link LessonCompletionView} per roster student (merged with any
 * persisted completion rows) plus any persisted rows for students who
 * are no longer on the roster (appended to the tail so historical reads
 * remain visible — same convention as the homework submission sheet).
 *
 * <p>{@code resources} is the ordered list of authored content blocks
 * attached to the lesson. May be empty (a lesson can legitimately be
 * instructions-only if the author hasn't added any blocks yet).
 */
public record LessonDetail(
        UUID id,
        UUID classId,
        UUID sessionId,
        String title,
        String instructions,
        UUID createdBy,
        Instant createdAt,
        Instant archivedAt,
        List<LessonCompletionView> completions,
        List<LessonResourceView> resources
) {}
