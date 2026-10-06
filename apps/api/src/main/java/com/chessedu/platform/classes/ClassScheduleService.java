package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.ClassSessionView;
import com.chessedu.platform.classes.dto.CreatePatternRequest;
import com.chessedu.platform.classes.dto.CreateSessionRequest;
import com.chessedu.platform.classes.dto.SchedulePatternView;
import com.chessedu.platform.classes.dto.UpdateSessionRequest;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.ClassSchedulePattern;
import com.chessedu.platform.classes.model.ClassSession;
import com.chessedu.platform.classes.model.ClassSessionStatus;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassSchedulePatternRepository;
import com.chessedu.platform.classes.repo.ClassSessionRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scheduling side of a class: recurring patterns + concrete sessions.
 *
 * <p>Two moving pieces:
 * <ul>
 *   <li><b>Patterns</b> describe a weekly rule in local time + IANA zone.
 *       Creating or editing a pattern triggers {@link #materialise} which
 *       generates one {@link ClassSession} per eligible weekday up to a
 *       horizon ({@code ends_on} or {@code today + 8 weeks}).</li>
 *   <li><b>Sessions</b> are concrete UTC instants. They can be ad-hoc
 *       (no {@code pattern_id}) or generated. When a pattern is edited,
 *       future sessions owned by it are regenerated <em>only if</em> they
 *       still look untouched (SCHEDULED status, no notes). Anything a human
 *       has modified — cancelled, completed, note-annotated — is preserved
 *       as an override the pattern will never overwrite.</li>
 * </ul>
 *
 * <p>Authorisation is enforced one layer up in the controller; this service
 * only enforces domain invariants (class exists in the given school, times
 * are sane, etc.).
 */
@Service
public class ClassScheduleService {

    /** How far into the future we pre-materialise an open-ended pattern. */
    public static final int DEFAULT_HORIZON_WEEKS = 8;

    /** Default IANA zone for patterns that omit one. */
    public static final String DEFAULT_TIMEZONE = "Asia/Tashkent";

    private final ClassGroupRepository classes;
    private final ClassSchedulePatternRepository patterns;
    private final ClassSessionRepository sessions;

    public ClassScheduleService(ClassGroupRepository classes,
                                ClassSchedulePatternRepository patterns,
                                ClassSessionRepository sessions) {
        this.classes = classes;
        this.patterns = patterns;
        this.sessions = sessions;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SchedulePatternView> listPatterns(UUID schoolId, UUID classId) {
        requireClass(schoolId, classId);
        return patterns.findByClassIdOrderByDayOfWeekAscStartTimeAsc(classId)
                .stream()
                .map(ClassScheduleService::toPatternView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClassSessionView> listSessions(UUID schoolId, UUID classId, boolean upcomingOnly) {
        requireClass(schoolId, classId);
        List<ClassSession> rows = upcomingOnly
                ? sessions.findByClassIdAndStartsAtGreaterThanEqualOrderByStartsAtAsc(classId, Instant.now())
                : sessions.findByClassIdOrderByStartsAtAsc(classId);
        return rows.stream().map(ClassScheduleService::toSessionView).toList();
    }

    // ------------------------------------------------------------------
    // Patterns
    // ------------------------------------------------------------------

    @Transactional
    public SchedulePatternView createPattern(UUID schoolId, UUID classId, CreatePatternRequest req) {
        requireClass(schoolId, classId);
        validatePatternTimes(req);

        ClassSchedulePattern p = new ClassSchedulePattern();
        p.setId(UUID.randomUUID());
        p.setClassId(classId);
        p.setDayOfWeek(req.dayOfWeek());
        p.setStartTime(req.startTime());
        p.setEndTime(req.endTime());
        p.setStartsOn(req.startsOn());
        p.setEndsOn(req.endsOn());
        p.setTimezone(resolveTimezone(req.timezone()));
        patterns.save(p);

        materialise(p);
        return toPatternView(p);
    }

    @Transactional
    public void deletePattern(UUID schoolId, UUID classId, UUID patternId) {
        requireClass(schoolId, classId);
        ClassSchedulePattern p = patterns.findById(patternId)
                .orElseThrow(() -> AppException.notFound("Schedule pattern"));
        if (!p.getClassId().equals(classId)) {
            throw AppException.notFound("Schedule pattern");
        }
        // FK on class_session.pattern_id is ON DELETE SET NULL — sessions stay,
        // detached, and the admin can still cancel or edit them one by one.
        patterns.delete(p);
    }

    // ------------------------------------------------------------------
    // Sessions (ad-hoc CRUD + edits to generated rows)
    // ------------------------------------------------------------------

    @Transactional
    public ClassSessionView createSession(UUID schoolId, UUID classId, CreateSessionRequest req) {
        requireClass(schoolId, classId);
        if (!req.endsAt().isAfter(req.startsAt())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "endsAt must be after startsAt");
        }
        ClassSession s = new ClassSession();
        s.setId(UUID.randomUUID());
        s.setClassId(classId);
        s.setPatternId(null); // ad-hoc
        s.setStartsAt(req.startsAt());
        s.setEndsAt(req.endsAt());
        s.setStatus(ClassSessionStatus.SCHEDULED);
        s.setNotes(emptyToNull(req.notes()));
        sessions.save(s);
        return toSessionView(s);
    }

    @Transactional
    public ClassSessionView updateSession(UUID schoolId, UUID classId, UUID sessionId, UpdateSessionRequest req) {
        requireClass(schoolId, classId);
        ClassSession s = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("Session"));
        if (!s.getClassId().equals(classId)) {
            throw AppException.notFound("Session");
        }

        Instant newStart = req.startsAt() != null ? req.startsAt() : s.getStartsAt();
        Instant newEnd   = req.endsAt()   != null ? req.endsAt()   : s.getEndsAt();
        if (!newEnd.isAfter(newStart)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "endsAt must be after startsAt");
        }
        s.setStartsAt(newStart);
        s.setEndsAt(newEnd);

        if (req.status() != null) {
            s.setStatus(req.status());
        }
        if (req.notes() != null) {
            // Explicit empty = clear. Anything else sets notes and marks the
            // session as a "human override" for pattern regeneration purposes
            // (see findPatternRegenerables).
            s.setNotes(emptyToNull(req.notes()));
        }
        sessions.save(s);
        return toSessionView(s);
    }

    @Transactional
    public void deleteSession(UUID schoolId, UUID classId, UUID sessionId) {
        requireClass(schoolId, classId);
        ClassSession s = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("Session"));
        if (!s.getClassId().equals(classId)) {
            throw AppException.notFound("Session");
        }
        sessions.delete(s);
    }

    // ------------------------------------------------------------------
    // Materialisation
    // ------------------------------------------------------------------

    /**
     * Walk from today (or the pattern's {@code startsOn}, whichever is later)
     * up to the horizon, and insert a session for every matching weekday
     * that doesn't already have one. Historical sessions (before today) are
     * never generated on pattern creation — if a school wants to backfill
     * attendance for past meetings, they can add ad-hoc sessions.
     *
     * <p>Regeneration safety: any existing future pattern-owned session that
     * is still SCHEDULED with no notes gets wiped and re-inserted. Anything
     * with a status change (cancelled/completed) or notes is preserved.
     */
    void materialise(ClassSchedulePattern p) {
        ZoneId zone = ZoneId.of(p.getTimezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate horizon = (p.getEndsOn() != null)
                ? p.getEndsOn()
                : today.plusWeeks(DEFAULT_HORIZON_WEEKS);
        LocalDate cursor = p.getStartsOn().isBefore(today) ? today : p.getStartsOn();

        // Advance cursor to the first matching weekday >= cursor.
        int targetDow = p.getDayOfWeekRaw(); // 1..7
        while (cursor.getDayOfWeek().getValue() != targetDow) {
            cursor = cursor.plusDays(1);
            if (cursor.isAfter(horizon)) break;
        }

        // Clear the regenerable slice before we re-insert.
        Instant now = Instant.now();
        List<ClassSession> stale = sessions.findPatternRegenerables(p.getId(), now);
        if (!stale.isEmpty()) {
            sessions.deleteByIds(stale.stream().map(ClassSession::getId).toList());
        }

        List<ClassSession> toInsert = new ArrayList<>();
        while (!cursor.isAfter(horizon)) {
            Instant startsAt = LocalDateTime.of(cursor, p.getStartTime()).atZone(zone).toInstant();
            Instant endsAt   = LocalDateTime.of(cursor, p.getEndTime()).atZone(zone).toInstant();

            // Skip if an override already exists at this exact instant for this pattern.
            // (It will either be cancelled/completed/noted — i.e. human-touched — and
            // stale-delete above already excluded it.)
            if (!sessions.existsByPatternIdAndStartsAt(p.getId(), startsAt)) {
                ClassSession s = new ClassSession();
                s.setId(UUID.randomUUID());
                s.setClassId(p.getClassId());
                s.setPatternId(p.getId());
                s.setStartsAt(startsAt);
                s.setEndsAt(endsAt);
                s.setStatus(ClassSessionStatus.SCHEDULED);
                toInsert.add(s);
            }
            cursor = cursor.plusWeeks(1);
        }
        if (!toInsert.isEmpty()) {
            sessions.saveAll(toInsert);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ClassGroup requireClass(UUID schoolId, UUID classId) {
        ClassGroup c = classes.findById(classId)
                .orElseThrow(() -> AppException.notFound("Class"));
        if (!c.getSchoolId().equals(schoolId)) {
            throw AppException.notFound("Class");
        }
        return c;
    }

    private static void validatePatternTimes(CreatePatternRequest req) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "endTime must be after startTime");
        }
        if (Duration.between(req.startTime(), req.endTime()).toMinutes() < 5) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Session must be at least 5 minutes");
        }
        if (req.endsOn() != null && req.endsOn().isBefore(req.startsOn())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "endsOn must be on or after startsOn");
        }
    }

    private static String resolveTimezone(String input) {
        if (input == null || input.isBlank()) return DEFAULT_TIMEZONE;
        try {
            ZoneId.of(input.trim());
        } catch (java.time.DateTimeException e) {
            // Covers both ZoneRulesException (unknown zone id) and
            // DateTimeException (malformed offset/id).
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Unknown timezone: " + input);
        }
        return input.trim();
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static SchedulePatternView toPatternView(ClassSchedulePattern p) {
        return new SchedulePatternView(
                p.getId(), p.getClassId(), p.getDayOfWeek(),
                p.getStartTime(), p.getEndTime(),
                p.getStartsOn(), p.getEndsOn(), p.getTimezone()
        );
    }

    private static ClassSessionView toSessionView(ClassSession s) {
        return new ClassSessionView(
                s.getId(), s.getClassId(), s.getPatternId(),
                s.getStartsAt(), s.getEndsAt(), s.getStatus(), s.getNotes()
        );
    }
}
