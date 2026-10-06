package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.Homework;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Homework}. The service layer is responsible
 * for authorisation and for merging active rows with per-student
 * submission state; this repo is a pure CRUD surface.
 */
public interface HomeworkRepository extends JpaRepository<Homework, UUID> {

    /**
     * Paged admin list for a class. The repository does not filter by
     * archived state — callers decide whether to include archived rows
     * (e.g. the admin "All" tab includes them, the "Active" tab does not).
     */
    Page<Homework> findByClassIdOrderByCreatedAtDesc(UUID classId, Pageable pageable);

    /** Admin list restricted to active (non-archived) homework. */
    Page<Homework> findByClassIdAndArchivedAtIsNullOrderByCreatedAtDesc(UUID classId, Pageable pageable);

    /**
     * Active homework across a set of classes — the student/parent rollup
     * starts here and pairs the result with submissions in the service.
     */
    List<Homework> findByClassIdInAndArchivedAtIsNullOrderByCreatedAtDesc(List<UUID> classIds);

    /** Existence probe used by the "which session has homework?" UI marker. */
    boolean existsBySessionId(UUID sessionId);

    Optional<Homework> findByIdAndClassId(UUID id, UUID classId);
}
