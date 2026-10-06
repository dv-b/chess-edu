package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.dto.ClassGroupSummary;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.ClassGroupStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassGroupRepository extends JpaRepository<ClassGroup, UUID> {

    /**
     * List classes in a school, optionally filtered by status and a
     * pre-built LIKE pattern (case-insensitive). Each row carries teacher
     * and student counts computed with correlated subqueries so we don't
     * pull rosters into memory for the list view.
     *
     * <p>Ordered by name ascending for a stable UI.
     *
     * <p><b>Why the pattern is pre-built:</b> when a null parameter is
     * wrapped by {@code concat('%', :search, '%')} inside JPQL, Postgres
     * can't resolve the operand type and defaults it to bytea — which then
     * explodes at {@code lower(bytea)}. Binding {@code :pattern} directly
     * next to the {@code like} operator pins the type to text even when
     * the value is null.
     */
    @Query("""
        select new com.chessedu.platform.classes.dto.ClassGroupSummary(
            c.id, c.name, c.level, c.status,
            (select count(t) from ClassTeacher t where t.id.classId = c.id),
            (select count(s) from ClassStudent s where s.id.classId = c.id),
            c.createdAt
        )
        from ClassGroup c
        where c.schoolId = :schoolId
          and (:status is null or c.status = :status)
          and (:pattern is null or lower(c.name) like :pattern)
        order by lower(c.name) asc
    """)
    List<ClassGroupSummary> findSummaries(@Param("schoolId") UUID schoolId,
                                          @Param("status") ClassGroupStatus status,
                                          @Param("pattern") String pattern);
}
