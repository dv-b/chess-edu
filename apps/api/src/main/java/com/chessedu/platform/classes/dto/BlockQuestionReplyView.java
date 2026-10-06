package com.chessedu.platform.classes.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read shape for one reply on a Q&amp;A thread.
 *
 * <p>{@code authorRole} is a snapshot label (STUDENT / TEACHER /
 * SCHOOL_ADMIN / PLATFORM_ADMIN) derived from the author's current
 * role at read time so the UI can colour the reply chrome ("teacher
 * answer" vs "student follow-up"). Same caveat as elsewhere: a former
 * student who's now a teacher will read as TEACHER on their old
 * replies. Acceptable trade-off for a small data model.
 */
public record BlockQuestionReplyView(
        UUID id,
        UUID questionId,
        UUID authorUserId,
        String authorDisplayName,
        String authorRole,
        String body,
        Instant createdAt
) { }
