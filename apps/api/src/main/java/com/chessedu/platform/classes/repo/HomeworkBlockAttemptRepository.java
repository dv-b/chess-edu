package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.HomeworkBlockAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link HomeworkBlockAttempt}.
 *
 * <p>Every "current-attempt" finder filters {@code replaced_by_id IS NULL} so
 * callers only see the live row in each history chain — matching the
 * semantics of the partial unique index
 * {@code homework_block_attempt_current_idx} in V008. Finders that return the
 * full chain (for the teacher's audit view) exist as explicit `*ByHomeworkId`
 * calls without that filter.
 *
 * <p>Authorisation (class membership, teacher assignment, student identity)
 * lives in the service layer — these methods are pure data access.
 */
public interface HomeworkBlockAttemptRepository
        extends JpaRepository<HomeworkBlockAttempt, UUID> {

    // ---- Current-attempt finders (replaced_by_id IS NULL) ------------------

    /**
     * The student's live attempt on a specific block, if any. Used by the
     * submit / reveal endpoints before deciding whether to insert a new row
     * or mark the old one as replaced.
     */
    @Query("""
           select a from HomeworkBlockAttempt a
            where a.homeworkId     = :homeworkId
              and a.studentUserId  = :studentUserId
              and a.blockIndex     = :blockIndex
              and a.replacedById is null
           """)
    Optional<HomeworkBlockAttempt> findCurrentForBlock(
            @Param("homeworkId") UUID homeworkId,
            @Param("studentUserId") UUID studentUserId,
            @Param("blockIndex") int blockIndex);

    /**
     * All live attempts for one student across every block of one homework —
     * drives the student's own detail view (block strip colouring + the
     * revisit flow per block).
     */
    @Query("""
           select a from HomeworkBlockAttempt a
            where a.homeworkId    = :homeworkId
              and a.studentUserId = :studentUserId
              and a.replacedById is null
           """)
    List<HomeworkBlockAttempt> findCurrentForStudent(
            @Param("homeworkId") UUID homeworkId,
            @Param("studentUserId") UUID studentUserId);

    /**
     * Every student's live attempts for one homework — the teacher
     * Submissions sheet with the 1–6 block strip per student.
     */
    @Query("""
           select a from HomeworkBlockAttempt a
            where a.homeworkId = :homeworkId
              and a.replacedById is null
           """)
    List<HomeworkBlockAttempt> findCurrentByHomeworkId(@Param("homeworkId") UUID homeworkId);

    /**
     * Rollup: one student's live attempts across many homework assignments.
     * Used by the student's list page to compute the block-state tally
     * ("2 of 6 needs work") without N+1 queries.
     */
    @Query("""
           select a from HomeworkBlockAttempt a
            where a.studentUserId = :studentUserId
              and a.homeworkId in :homeworkIds
              and a.replacedById is null
           """)
    List<HomeworkBlockAttempt> findCurrentForStudentAcrossHomework(
            @Param("studentUserId") UUID studentUserId,
            @Param("homeworkIds") List<UUID> homeworkIds);

    /**
     * Rollup: live attempts across many homework assignments, any student.
     * Used by the admin list to compute per-assignment tallies that include
     * block-level review state, not just the legacy DONE/SKIPPED self-mark.
     */
    @Query("""
           select a from HomeworkBlockAttempt a
            where a.homeworkId in :homeworkIds
              and a.replacedById is null
           """)
    List<HomeworkBlockAttempt> findCurrentByHomeworkIdIn(@Param("homeworkIds") List<UUID> homeworkIds);

    // ---- History finders (include replaced rows) ---------------------------

    /**
     * Full history for one (homework, student, block) triple, ordered oldest
     * first. Lets the teacher scroll back through prior attempts on a single
     * block.
     */
    List<HomeworkBlockAttempt> findByHomeworkIdAndStudentUserIdAndBlockIndexOrderByCreatedAtAsc(
            UUID homeworkId, UUID studentUserId, int blockIndex);

    /**
     * Every attempt (including superseded ones) for a homework — used when a
     * homework is deleted or when the teacher opens the audit / history
     * view. CASCADE on the FK already handles hard deletes, so this is
     * primarily a read path.
     */
    List<HomeworkBlockAttempt> findByHomeworkId(UUID homeworkId);
}
