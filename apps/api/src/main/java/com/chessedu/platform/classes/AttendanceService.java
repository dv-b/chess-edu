package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.AttendanceMarkView;
import com.chessedu.platform.classes.dto.AttendanceSheetView;
import com.chessedu.platform.classes.dto.ClassRosterMember;
import com.chessedu.platform.classes.dto.UpdateAttendanceRequest;
import com.chessedu.platform.classes.model.AttendanceStatus;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.ClassSession;
import com.chessedu.platform.classes.model.ClassSessionStatus;
import com.chessedu.platform.classes.model.SessionAttendance;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassSessionRepository;
import com.chessedu.platform.classes.repo.ClassStudentRepository;
import com.chessedu.platform.classes.repo.ClassTeacherRepository;
import com.chessedu.platform.classes.repo.SessionAttendanceRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write the attendance sheet for a single {@link ClassSession}.
 *
 * <p>Authorisation model (enforced here because the rules are non-trivial):
 * <ul>
 *   <li><b>PLATFORM_ADMIN</b> may read/write any class in any school, but must
 *       pass the school id through to the controller layer.</li>
 *   <li><b>SCHOOL_ADMIN</b> may read/write any class in their own school.</li>
 *   <li><b>TEACHER</b> may read/write only classes they're assigned to. This
 *       is the most-common path — teachers live in attendance during lessons.</li>
 *   <li>Everyone else is forbidden.</li>
 * </ul>
 *
 * <p>A session whose {@link ClassSessionStatus#CANCELLED} cannot accept marks
 * — it didn't happen, so attendance is meaningless. Reads still work so
 * history stays visible after a late cancellation.
 */
@Service
public class AttendanceService {

    private final ClassGroupRepository classes;
    private final ClassSessionRepository sessions;
    private final ClassStudentRepository rosters;
    private final ClassTeacherRepository teacherRosters;
    private final SessionAttendanceRepository attendance;

    public AttendanceService(ClassGroupRepository classes,
                             ClassSessionRepository sessions,
                             ClassStudentRepository rosters,
                             ClassTeacherRepository teacherRosters,
                             SessionAttendanceRepository attendance) {
        this.classes = classes;
        this.sessions = sessions;
        this.rosters = rosters;
        this.teacherRosters = teacherRosters;
        this.attendance = attendance;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Build the attendance sheet: every roster student plus their current mark
     * (or null if not yet marked), in roster order. Students can be in the
     * mark table but no longer on the roster (e.g. they moved classes after
     * attendance was recorded); those rows are appended at the end of the
     * list so the historical trail isn't hidden.
     */
    @Transactional(readOnly = true)
    public AttendanceSheetView read(AuthenticatedUser principal, UUID classId, UUID sessionId) {
        Lookup ctx = loadAndAuthorise(principal, classId, sessionId, /* writing */ false);

        List<ClassRosterMember> roster = rosters.findStudents(classId);
        List<SessionAttendance> marks = attendance.findBySessionId(sessionId);

        Map<UUID, SessionAttendance> byStudent = new HashMap<>(marks.size());
        for (SessionAttendance a : marks) {
            byStudent.put(a.getStudentUserId(), a);
        }

        // Roster order first.
        Map<UUID, AttendanceMarkView> ordered = new LinkedHashMap<>(roster.size());
        for (ClassRosterMember m : roster) {
            SessionAttendance mark = byStudent.get(m.userId());
            ordered.put(m.userId(), toView(m.userId(), m.displayName(), mark));
        }

        // Tail: marked students that have since left the roster. Rare, but we
        // keep their mark visible rather than dropping it silently.
        for (SessionAttendance a : marks) {
            if (!ordered.containsKey(a.getStudentUserId())) {
                ordered.put(
                        a.getStudentUserId(),
                        toView(a.getStudentUserId(), null /* displayName unknown */, a)
                );
            }
        }

        return new AttendanceSheetView(
                sessionId,
                classId,
                ctx.session.getStartsAt(),
                ctx.session.getEndsAt(),
                ctx.session.getStatus(),
                new ArrayList<>(ordered.values())
        );
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Bulk upsert. For each entry:
     * <ul>
     *   <li>If {@code status} is non-null: upsert a row for that student.</li>
     *   <li>If {@code status} is null: clear any existing mark for that
     *       student (setting them back to "unmarked").</li>
     * </ul>
     *
     * <p>Students not in the request are left alone. We reject entries for
     * students that aren't on the class's current roster — the UI should
     * never surface them as options, and allowing writes would create
     * orphaned marks that only show up in the roster-leaver tail.
     */
    @Transactional
    public AttendanceSheetView update(AuthenticatedUser principal,
                                      UUID classId,
                                      UUID sessionId,
                                      UpdateAttendanceRequest req) {
        Lookup ctx = loadAndAuthorise(principal, classId, sessionId, /* writing */ true);

        if (ctx.session.getStatus() == ClassSessionStatus.CANCELLED) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Cannot record attendance on a cancelled session");
        }

        // Snapshot the roster once so we reject unknown students before
        // touching the database.
        List<ClassRosterMember> roster = rosters.findStudents(classId);
        java.util.Set<UUID> rosterIds = new java.util.HashSet<>(roster.size());
        for (ClassRosterMember m : roster) rosterIds.add(m.userId());

        Instant now = Instant.now();
        for (UpdateAttendanceRequest.Entry e : req.entries()) {
            if (!rosterIds.contains(e.studentUserId())) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "Student is not on the class roster");
            }

            if (e.status() == null) {
                // Clear the mark.
                attendance.deleteBySessionAndStudent(sessionId, e.studentUserId());
                continue;
            }

            SessionAttendance row = attendance
                    .findBySessionIdAndStudentUserId(sessionId, e.studentUserId())
                    .orElseGet(() -> {
                        SessionAttendance s = new SessionAttendance();
                        s.setId(UUID.randomUUID());
                        s.setSessionId(sessionId);
                        s.setStudentUserId(e.studentUserId());
                        return s;
                    });
            row.setStatus(e.status());
            row.setNote(emptyToNull(e.note()));
            row.setMarkedBy(principal.userId());
            row.setMarkedAt(now);
            attendance.save(row);
        }

        // Re-read so the client gets the authoritative post-write sheet.
        return read(principal, classId, sessionId);
    }

    // ------------------------------------------------------------------
    // Authorisation + lookup
    // ------------------------------------------------------------------

    private record Lookup(ClassGroup clazz, ClassSession session) {}

    /**
     * Centralise the "find the session, confirm the caller may touch it" check.
     * Throws 404 when the class or session can't be found in the scoped school
     * (rather than 403) to avoid leaking existence across tenants.
     */
    private Lookup loadAndAuthorise(AuthenticatedUser principal,
                                    UUID classId,
                                    UUID sessionId,
                                    boolean writing) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }

        ClassGroup clazz = classes.findById(classId)
                .orElseThrow(() -> AppException.notFound("Class"));

        // School scoping. PLATFORM_ADMIN sees everything; everyone else must
        // be in the same school as the class.
        boolean platformAdmin = principal.platformRole() == PlatformRole.PLATFORM_ADMIN;
        if (!platformAdmin) {
            if (principal.schoolId() == null
                    || !clazz.getSchoolId().equals(principal.schoolId())) {
                throw AppException.notFound("Class");
            }
        }

        // Role gate.
        Role role = principal.role();
        if (!platformAdmin) {
            if (role == Role.SCHOOL_ADMIN) {
                // always allowed within the school.
            } else if (role == Role.TEACHER) {
                boolean assigned = teacherRosters.existsByIdClassIdAndIdTeacherUserId(
                        classId, principal.userId());
                if (!assigned) {
                    throw AppException.forbidden("Teachers may only mark attendance for their own classes");
                }
            } else {
                throw AppException.forbidden("Not allowed to view attendance");
            }
        }

        ClassSession session = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("Session"));
        if (!session.getClassId().equals(classId)) {
            // Session belongs to a different class; 404 rather than 400 to
            // keep the URL-poking surface flat.
            throw AppException.notFound("Session");
        }

        // Writing has an extra domain-level check (cancelled sessions can't be
        // marked) but it lives in the caller since only writes care.
        // For reads of any non-cancelled session we're done.
        if (writing && role == Role.TEACHER) {
            // Re-check teacher assignment was done above; no extra work.
        }

        return new Lookup(clazz, session);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static AttendanceMarkView toView(UUID studentId, String displayName, SessionAttendance mark) {
        if (mark == null) {
            return new AttendanceMarkView(studentId, displayName, null, null, null, null);
        }
        return new AttendanceMarkView(
                studentId,
                displayName,
                mark.getStatus(),
                mark.getNote(),
                mark.getMarkedBy(),
                mark.getMarkedAt()
        );
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
