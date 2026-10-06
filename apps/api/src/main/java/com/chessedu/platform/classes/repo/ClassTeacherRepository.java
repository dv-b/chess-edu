package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.dto.ClassRosterMember;
import com.chessedu.platform.classes.model.ClassTeacher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassTeacherRepository
        extends JpaRepository<ClassTeacher, ClassTeacher.ClassTeacherId> {

    /**
     * Roster projection joining class_teacher → app_user so the UI can show
     * names and emails without a second round-trip. Ordered by displayName
     * for a stable table.
     */
    @Query("""
        select new com.chessedu.platform.classes.dto.ClassRosterMember(
            u.id, u.email, u.displayName
        )
        from ClassTeacher ct, com.chessedu.platform.identity.model.User u
        where u.id = ct.id.teacherUserId
          and ct.id.classId = :classId
        order by lower(u.displayName) asc
    """)
    List<ClassRosterMember> findTeachers(@Param("classId") UUID classId);

    boolean existsByIdClassIdAndIdTeacherUserId(UUID classId, UUID teacherUserId);

    @Modifying
    @Query("delete from ClassTeacher ct where ct.id.classId = :classId and ct.id.teacherUserId = :userId")
    int delete(@Param("classId") UUID classId, @Param("userId") UUID userId);
}
