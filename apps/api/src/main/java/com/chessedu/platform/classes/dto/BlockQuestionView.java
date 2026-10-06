package com.chessedu.platform.classes.dto;

import com.chessedu.platform.classes.model.BlockQuestionParentKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Read shape for the head of one Q&amp;A thread.
 *
 * <p>Carries the asker's display name as a denormalised convenience —
 * the inbox would otherwise need a second batch lookup against
 * {@code app_user} per thread. The role is omitted because it can
 * change over time; the UI shows "student" in the chrome based on the
 * `studentUserId` slot.
 */
public record BlockQuestionView(
        UUID id,
        UUID classId,
        BlockQuestionParentKind parentKind,
        UUID parentId,
        int blockIndex,
        UUID studentUserId,
        String studentDisplayName,
        String body,
        Instant resolvedAt,
        UUID resolvedBy,
        String resolvedByDisplayName,
        Instant createdAt,
        Instant updatedAt
) { }
