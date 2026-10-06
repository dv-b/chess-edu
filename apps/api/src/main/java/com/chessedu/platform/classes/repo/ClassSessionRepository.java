package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.ClassSession;
import com.chessedu.platform.classes.model.ClassSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {

    /** Timeline for a single class, chronological. */
    List<ClassSession> findByClassIdOrderByStartsAtAsc(UUID classId);

    /** Upcoming sessions for a class (starts_at >= now), chronological. */
    List<ClassSession> findByClassIdAndStartsAtGreaterThanEqualOrderByStartsAtAsc(
            UUID classId, Instant from);

    /**
     * All sessions owned by a pattern that are eligible for regeneration:
     * future in time AND untouched (SCHEDULED status AND notes IS NULL).
     * Anything cancelled, completed, or with notes is preserved across
     * pattern edits as a human-curated override.
     */
    @Query("""
        select s from ClassSession s
        where s.patternId = :patternId
          and s.startsAt >= :from
          and s.status = com.chessedu.platform.classes.model.ClassSessionStatus.SCHEDULED
          and s.notes is null
    """)
    List<ClassSession> findPatternRegenerables(@Param("patternId") UUID patternId,
                                                @Param("from") Instant from);

    /** Bulk delete helper used when regenerating a pattern's sessions. */
    @Modifying
    @Query("delete from ClassSession s where s.id in :ids")
    int deleteByIds(@Param("ids") List<UUID> ids);

    long countByClassIdAndStatus(UUID classId, ClassSessionStatus status);

    boolean existsByPatternIdAndStartsAt(UUID patternId, Instant startsAt);
}
