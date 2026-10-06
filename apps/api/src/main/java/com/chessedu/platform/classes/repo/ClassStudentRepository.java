package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.dto.ClassRosterMember;
import com.chessedu.platform.classes.model.ClassStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassStudentRepository
        extends JpaRepository<ClassStudent, ClassStudent.ClassStudentId> {

    @Query("""
        select new com.chessedu.platform.classes.dto.ClassRosterMember(
            u.id, u.email, u.displayName
        )
        from ClassStudent cs, com.chessedu.platform.identity.model.User u
        where u.id = cs.id.studentUserId
          and cs.id.classId = :classId
        order by lower(u.displayName) asc
    """)
    List<ClassRosterMember> findStudents(@Param("classId") UUID classId);

    boolean existsByIdClassIdAndIdStudentUserId(UUID classId, UUID studentUserId);

    /**
     * Classes a student is currently enrolled in — the inverse of
     * {@link #findStudents(UUID)}. Returns raw join rows; callers pull the
     * {@code classId} out of {@code id}.
     */
    List<ClassStudent> findByIdStudentUserId(UUID studentUserId);

    @Modifying
    @Query("delete from ClassStudent cs where cs.id.classId = :classId and cs.id.studentUserId = :userId")
    int delete(@Param("classId") UUID classId, @Param("userId") UUID userId);
}
