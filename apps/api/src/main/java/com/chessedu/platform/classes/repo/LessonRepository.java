package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.Lesson;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Lesson}. Mirrors {@link HomeworkRepository}:
 * the service layer owns authorisation and any per-student / per-class
 * roll-ups, this is a pure CRUD surface.
 */
public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    /**
     * Paged admin list for a class. The repository does not filter by
     * archived state — callers decide whether to include archived rows
     * (the admin "All" tab does, the default "Active" tab does not).
     */
    Page<Lesson> findByClassIdOrderByCreatedAtDesc(UUID classId, Pageable pageable);

    /** Admin list restricted to active (non-archived) lessons. */
    Page<Lesson> findByClassIdAndArchivedAtIsNullOrderByCreatedAtDesc(UUID classId, Pageable pageable);

    /**
     * Active lessons across a set of classes — the student-side list
     * starts here and pairs the result with completions in the service.
     */
    List<Lesson> findByClassIdInAndArchivedAtIsNullOrderByCreatedAtDesc(List<UUID> classIds);

    /** Existence probe used by the "which session has lessons?" UI marker. */
    boolean existsBySessionId(UUID sessionId);

    Optional<Lesson> findByIdAndClassId(UUID id, UUID classId);
}
