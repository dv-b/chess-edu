package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.HomeworkResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for {@link HomeworkResource}. Resources are always
 * manipulated as a list scoped to a homework — the service uses
 * replace-on-update semantics, so the only write we need beyond the
 * standard JpaRepository surface is a bulk delete-by-homework.
 */
public interface HomeworkResourceRepository extends JpaRepository<HomeworkResource, UUID> {

    /** Ordered list of blocks for a homework. Primary read path. */
    List<HomeworkResource> findByHomeworkIdOrderByPositionAsc(UUID homeworkId);

    /**
     * Flat list of homework IDs (one entry per block) across the given
     * homework set. The student / parent rollup counts occurrences in memory
     * to derive per-homework block counts without loading full payloads —
     * resource payload can be large (JSONB board + moves + solution), so
     * selecting only the id keeps the rollup cheap.
     */
    @Query("select r.homeworkId from HomeworkResource r where r.homeworkId in :homeworkIds")
    List<UUID> findHomeworkIdsByHomeworkIdIn(@Param("homeworkIds") List<UUID> homeworkIds);

    /**
     * Bulk-delete all resources for a homework. Used during the
     * replace-on-update save path: the service deletes everything for the
     * homework and re-inserts the new list inside a single transaction.
     *
     * <p>Annotated with {@link Modifying} so Spring Data treats this as a
     * DML statement rather than trying to fetch rows; {@code clearAutomatically}
     * keeps the persistence context from returning stale entities after the
     * delete, and {@code flushAutomatically} ensures any pending inserts are
     * flushed before the bulk delete runs.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM HomeworkResource r WHERE r.homeworkId = :homeworkId")
    int deleteAllByHomeworkId(UUID homeworkId);
}
