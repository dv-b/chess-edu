package com.chessedu.platform.classes.repo;

import com.chessedu.platform.classes.model.SessionAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for attendance marks. The service layer is responsible
 * for authorisation; this repo is a pure CRUD surface.
 */
public interface SessionAttendanceRepository
        extends JpaRepository<SessionAttendance, UUID> {

    /** All marks for one session — used to build the roster view. */
    List<SessionAttendance> findBySessionId(UUID sessionId);

    /** Single mark lookup for upsert-by-natural-key. */
    Optional<SessionAttendance> findBySessionIdAndStudentUserId(UUID sessionId, UUID studentUserId);

    /** Used by the "clear a mark" code path. */
    @Modifying
    @Query("delete from SessionAttendance a where a.sessionId = :sessionId and a.studentUserId = :studentUserId")
    int deleteBySessionAndStudent(@Param("sessionId") UUID sessionId, @Param("studentUserId") UUID studentUserId);
}
