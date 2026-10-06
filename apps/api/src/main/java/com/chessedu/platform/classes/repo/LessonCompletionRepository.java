package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.LessonCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for per-student lesson read state. As with
 * {@link HomeworkSubmissionRepository}, a missing row means
 * "not yet started". The service layer enforces authorisation and
 * builds the roster-aware sheet on top of these calls.
 */
public interface LessonCompletionRepository
        extends JpaRepository<LessonCompletion, UUID> {

    /** All completion rows for a single lesson — the admin detail view. */
    List<LessonCompletion> findByLessonId(UUID lessonId);

    /** Single-row lookup for upsert-by-natural-key. */
    Optional<LessonCompletion> findByLessonIdAndStudentUserId(UUID lessonId, UUID studentUserId);

    /**
     * Batch completion fetch for one student across a set of lesson ids —
     * used to build the student-side roll-up without an ad-hoc JPQL JOIN.
     */
    List<LessonCompletion> findByStudentUserIdAndLessonIdIn(UUID studentUserId, List<UUID> lessonIds);

    /**
     * Batch completion fetch across a set of lesson ids — used to compute
     * per-lesson roll-up tallies on the admin list (e.g. "3 of 7 students
     * have read this").
     */
    List<LessonCompletion> findByLessonIdIn(List<UUID> lessonIds);

    /** Used by "reset my completion" when a student clears their state. */
    @Modifying
    @Query("delete from LessonCompletion c where c.lessonId = :lessonId and c.studentUserId = :studentUserId")
    int deleteByLessonAndStudent(@Param("lessonId") UUID lessonId,
                                 @Param("studentUserId") UUID studentUserId);
}
