package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.LessonResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for {@link LessonResource}. Resources are always
 * manipulated as a list scoped to a lesson — the service uses
 * replace-on-update semantics, so the only write we need beyond the
 * standard {@code JpaRepository} surface is a bulk delete-by-lesson.
 * Mirrors {@link HomeworkResourceRepository}.
 */
public interface LessonResourceRepository extends JpaRepository<LessonResource, UUID> {

    /** Ordered list of blocks for a lesson. Primary read path. */
    List<LessonResource> findByLessonIdOrderByPositionAsc(UUID lessonId);

    /**
     * Flat list of lesson IDs (one entry per block) across the given
     * lesson set. The student-side roll-up counts occurrences in memory
     * to derive per-lesson block counts without loading full payloads —
     * PROSE blocks in particular can be large.
     */
    @Query("select r.lessonId from LessonResource r where r.lessonId in :lessonIds")
    List<UUID> findLessonIdsByLessonIdIn(@Param("lessonIds") List<UUID> lessonIds);

    /**
     * Bulk-delete all resources for a lesson. Used during the
     * replace-on-update save path: the service deletes everything for
     * the lesson and re-inserts the new list inside a single
     * transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LessonResource r WHERE r.lessonId = :lessonId")
    int deleteAllByLessonId(UUID lessonId);
}
