package com.chessedu.platform.classes.dto;

/**
 * Write shape for posting a reply to a Q&amp;A thread.
 *
 * <p>Body must be non-blank and ≤2000 chars after trim — same caps
 * the schema CHECK constraints enforce. Service layer validates so we
 * can return a clean {@code VALIDATION_FAILED} rather than a generic
 * DB constraint error.
 */
public record CreateBlockQuestionReplyRequest(String body) { }
