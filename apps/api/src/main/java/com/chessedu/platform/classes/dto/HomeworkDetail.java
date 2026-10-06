package com.chessedu.platform.classes.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full admin detail for one homework: the assignment plus one
 * {@link HomeworkSubmissionView} per roster student (merged with any
 * persisted submission rows) plus any persisted rows for students who are
 * no longer on the roster (appended to the tail so historical submissions
 * remain visible, mirroring the attendance sheet convention).
 *
 * <p>{@code resources} is the ordered list of authored content blocks
 * attached to the homework (chess positions, etc.). May be empty.
 *
 * <p>{@code attempts} is the flat list of every student's <em>live</em>
 * block-level attempt ({@code replaced_by_id IS NULL}) across the whole
 * homework. The admin Submissions sheet groups them by
 * {@code studentUserId} to colour the 1–N block strip per student without
 * a second round-trip. Historical (replaced) attempts are fetched lazily
 * via a dedicated audit endpoint when the teacher asks to see a chain.
 */
public record HomeworkDetail(
        UUID id,
        UUID classId,
        UUID sessionId,
        String title,
        String instructions,
        Instant dueAt,
        UUID createdBy,
        Instant createdAt,
        Instant archivedAt,
        List<HomeworkSubmissionView> submissions,
        List<HomeworkResourceView> resources,
        List<BlockAttemptView> attempts
) {}
