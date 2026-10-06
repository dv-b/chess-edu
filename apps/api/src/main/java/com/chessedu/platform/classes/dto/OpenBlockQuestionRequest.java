package com.chessedu.platform.classes.dto;

/**
 * Write shape for opening a new Q&amp;A thread on a specific block.
 *
 * <p>The parent kind, parent id, and block index come from the URL —
 * the request body only carries the question text. Body is required,
 * non-blank, ≤2000 chars after trim (matches the schema CHECK + the
 * service-level {@code requireBody} validator).
 */
public record OpenBlockQuestionRequest(String body) { }
