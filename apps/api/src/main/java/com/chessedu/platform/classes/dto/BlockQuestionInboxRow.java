package com.chessedu.platform.classes.dto;

import java.time.Instant;

/**
 * One row in the teacher inbox. Carries the question head + a thin
 * preview ({@code replyCount} and {@code lastReplyAt}) so the UI can
 * render "Italian Game · block 2 · 3 replies · last: 2 h ago" without
 * fetching the full reply chain. The full chain is one click /
 * round-trip away via the single-thread endpoint.
 *
 * <p>Threads on archived parents are filtered out at the service
 * layer — a teacher shouldn't see questions on a homework they've
 * archived. The historical thread is still readable via the question
 * id endpoint if needed.
 */
public record BlockQuestionInboxRow(
        BlockQuestionView question,
        String parentTitle,
        int replyCount,
        Instant lastReplyAt,
        String lastReplyAuthorDisplayName
) { }
