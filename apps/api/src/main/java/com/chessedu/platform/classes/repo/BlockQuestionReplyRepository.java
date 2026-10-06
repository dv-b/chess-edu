package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.BlockQuestionReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for replies under a {@link com.chessedu.platform.classes.model.BlockQuestion}.
 *
 * <p>Replies are immutable in v1 — only insert + read. Bulk delete is
 * provided for the future "hard-delete a question" path (which would
 * cascade via the FK anyway, but the explicit method makes intent
 * clear when called from service code).
 */
public interface BlockQuestionReplyRepository extends JpaRepository<BlockQuestionReply, UUID> {

    /**
     * Thread reconstruction — every reply on one question, oldest first.
     * Backed by {@code block_question_reply_thread_idx}.
     */
    List<BlockQuestionReply> findByQuestionIdOrderByCreatedAtAsc(UUID questionId);

    /**
     * Batch reply fetch for the class inbox — given the list of
     * unresolved question ids, return every reply across them in one
     * round-trip. The service groups them client-side via
     * {@code questionId} to render thread previews ("last reply 2 h
     * ago by Anna") without N+1 queries.
     */
    List<BlockQuestionReply> findByQuestionIdInOrderByCreatedAtAsc(List<UUID> questionIds);

    /**
     * Count signal — used to derive "thread has N replies" tallies
     * for the inbox preview without paging the bodies.
     */
    long countByQuestionId(UUID questionId);

    /**
     * Used when a future "delete this question" admin path lands.
     * Bulk delete of replies on a question id; FK cascade does the
     * same job, but having the explicit method makes the intent
     * legible at call sites that need to wipe replies before
     * deleting the question (e.g. a future "merge two threads"
     * operation).
     */
    @Modifying
    @Query("delete from BlockQuestionReply r where r.questionId = :questionId")
    int deleteByQuestionId(@Param("questionId") UUID questionId);
}
