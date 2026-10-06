package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.BlockQuestion;
import com.chessedu.platform.classes.model.BlockQuestionParentKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the head of a block-level Q&amp;A thread. Replies
 * live in {@link BlockQuestionReplyRepository}; the two are joined by
 * {@code question_id} at the service layer because Spring Data's lazy
 * loading would force a session-aware fetch path that complicates
 * batch reads.
 *
 * <p>Three primary access patterns:
 * <ul>
 *   <li><b>Student-side</b>: "show me my threads on this block of this
 *       parent". Hit via
 *       {@link #findByParentKindAndParentIdAndBlockIndexAndStudentUserId}.</li>
 *   <li><b>Per-block teacher overlay</b>: "show every student's thread
 *       on this block". Hit via
 *       {@link #findByParentKindAndParentIdAndBlockIndex}.</li>
 *   <li><b>Class-wide inbox</b>: "show me unresolved threads in this
 *       class, oldest first". Hit via {@link #findClassInbox} which
 *       leans on the partial index in V011.</li>
 * </ul>
 */
public interface BlockQuestionRepository extends JpaRepository<BlockQuestion, UUID> {

    /**
     * Single student's threads on one block — student-side read. Returns
     * both open and resolved rows; the caller chooses which to surface.
     * Ordered oldest-first so a UI can render the trail in chronological
     * order without re-sorting.
     */
    List<BlockQuestion> findByParentKindAndParentIdAndBlockIndexAndStudentUserIdOrderByCreatedAtAsc(
            BlockQuestionParentKind parentKind,
            UUID parentId,
            int blockIndex,
            UUID studentUserId);

    /**
     * Every student's threads on one block — admin / teacher overlay on
     * the block-review surface. Both open + resolved.
     */
    List<BlockQuestion> findByParentKindAndParentIdAndBlockIndexOrderByCreatedAtAsc(
            BlockQuestionParentKind parentKind,
            UUID parentId,
            int blockIndex);

    /**
     * Class inbox — unresolved threads sorted oldest-first. Backed by
     * the partial index {@code block_question_class_open_idx} so the
     * cost stays constant as the resolved-thread archive grows.
     */
    List<BlockQuestion> findByClassIdAndResolvedAtIsNullOrderByCreatedAtAsc(UUID classId);

    /**
     * Batch finder used by the per-class inbox roll-up to discover
     * which parent rows have unresolved threads. Matches one parent
     * kind at a time so the caller can hit homework + lesson without
     * a UNION query (the query planner does better with two simple
     * lookups against the composite index).
     */
    List<BlockQuestion> findByClassIdAndParentKindAndResolvedAtIsNull(
            UUID classId, BlockQuestionParentKind parentKind);

    /**
     * Inbox count signal — admin sidebar badge. Same partial-index
     * lean as the list finder.
     */
    long countByClassIdAndResolvedAtIsNull(UUID classId);

    /**
     * All threads opened by one student across every class they're
     * enrolled in, newest first. Powers the cross-class "My questions"
     * rollup view (Lane B / B4). Includes both open and resolved
     * threads — the surface lets the student see where a teacher has
     * replied and where they've already self-resolved.
     */
    List<BlockQuestion> findByStudentUserIdOrderByCreatedAtDesc(UUID studentUserId);
}
