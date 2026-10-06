package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.HomeworkSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for per-student submission state. As with attendance,
 * a missing row means "not yet started". The service layer enforces
 * authorisation and builds the roster-aware sheet on top of these calls.
 */
public interface HomeworkSubmissionRepository
        extends JpaRepository<HomeworkSubmission, UUID> {

    /** All submission rows for a single assignment — the admin detail view. */
    List<HomeworkSubmission> findByHomeworkId(UUID homeworkId);

    /** Single row lookup for upsert-by-natural-key. */
    Optional<HomeworkSubmission> findByHomeworkIdAndStudentUserId(UUID homeworkId, UUID studentUserId);

    /**
     * Batch submission fetch for one student across a set of homework ids —
     * used to build the student / parent rollup without an ad-hoc JPQL JOIN.
     */
    List<HomeworkSubmission> findByStudentUserIdAndHomeworkIdIn(UUID studentUserId, List<UUID> homeworkIds);

    /**
     * Batch submission fetch across a set of homework ids — used to compute
     * per-assignment roll-up tallies on the admin list.
     */
    List<HomeworkSubmission> findByHomeworkIdIn(List<UUID> homeworkIds);

    /** Used by "reset my submission" when a student clears their state. */
    @Modifying
    @Query("delete from HomeworkSubmission s where s.homeworkId = :homeworkId and s.studentUserId = :studentUserId")
    int deleteByHomeworkAndStudent(@Param("homeworkId") UUID homeworkId,
                                   @Param("studentUserId") UUID studentUserId);
}
