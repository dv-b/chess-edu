package com.chessedu.platform.classes.dto;

import java.util.List;

/**
 * Full thread view: question head + ordered replies, oldest first.
 *
 * <p>The single composite view returned by reply / resolve endpoints
 * so the UI can repaint without a second round-trip. {@code parentTitle}
 * is the title of the homework or lesson the thread is pinned to —
 * denormalised at read time so the inbox / overlay can render
 * "Italian Game · block 2" without a second lookup. Null only if the
 * parent has been hard-deleted (which doesn't currently happen — both
 * homework and lesson archive instead).
 */
public record BlockQuestionThreadView(
        BlockQuestionView question,
        String parentTitle,
        List<BlockQuestionReplyView> replies
) { }
