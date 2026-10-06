package com.chessedu.platform.classes;

import com.chessedu.platform.classes.chess.LessonResourceValidator;
import com.chessedu.platform.classes.dto.ClassRosterMember;
import com.chessedu.platform.classes.dto.CreateLessonRequest;
import com.chessedu.platform.classes.dto.LessonCompletionView;
import com.chessedu.platform.classes.dto.LessonDetail;
import com.chessedu.platform.classes.dto.LessonResourceInput;
import com.chessedu.platform.classes.dto.LessonResourceView;
import com.chessedu.platform.classes.dto.LessonSummary;
import com.chessedu.platform.classes.dto.StudentLessonDetail;
import com.chessedu.platform.classes.dto.StudentLessonView;
import com.chessedu.platform.classes.dto.UpdateLessonRequest;
import com.chessedu.platform.classes.dto.UpdateMyCompletionRequest;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.ClassSession;
import com.chessedu.platform.classes.model.ClassStudent;
import com.chessedu.platform.classes.model.Lesson;
import com.chessedu.platform.classes.model.LessonCompletion;
import com.chessedu.platform.classes.model.LessonCompletionStatus;
import com.chessedu.platform.classes.model.LessonResource;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassSessionRepository;
import com.chessedu.platform.classes.repo.ClassStudentRepository;
import com.chessedu.platform.classes.repo.ClassTeacherRepository;
import com.chessedu.platform.classes.repo.LessonCompletionRepository;
import com.chessedu.platform.classes.repo.LessonRepository;
import com.chessedu.platform.classes.repo.LessonResourceRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Read/write lessons (study-material assignments) and per-student
 * completion state for a single class. Sibling to {@link HomeworkService};
 * the authorisation model, soft-delete semantics, and three-state PATCH
 * behaviour on {@code resources} are all identical — the entities are
 * the ones that differ (no {@code dueAt}, lighter completion shape).
 *
 * <p>Authorisation model (enforced here):
 * <ul>
 *   <li><b>PLATFORM_ADMIN</b> may read/write any class in any school.</li>
 *   <li><b>SCHOOL_ADMIN</b> may read/write any class in their own school.</li>
 *   <li><b>TEACHER</b> may read/write only classes they're assigned to.</li>
 *   <li>Everyone else is forbidden from these admin-side endpoints.</li>
 * </ul>
 *
 * <p>A lesson with {@code archivedAt != null} is hidden from the default
 * admin list (the "Active" tab) but kept around for historical reports.
 * Teachers cannot hard-delete a lesson — the read-trail is more valuable
 * than the mess of a dangling completion row.
 *
 * <p>The {@link #authoriseClass(AuthenticatedUser, UUID)} /
 * {@link #validateOptionalSession(UUID, UUID)} / {@link #requireTitle(String)}
 * / {@link #emptyToNull(String)} helpers mirror the homework service.
 * A future refactor could consolidate these into a shared base /
 * utility class; leaving them duplicated for now matches how the
 * #29–#35 block-attempt service also duplicated them, and keeps the
 * diff surface of this task tight.
 */
@Service
public class LessonService {

    private static final int MAX_TITLE_LEN = 200;

    private final ClassGroupRepository classes;
    private final ClassSessionRepository sessions;
    private final ClassStudentRepository rosters;
    private final ClassTeacherRepository teacherRosters;
    private final LessonRepository lessons;
    private final LessonResourceRepository resources;
    private final LessonCompletionRepository completions;
    private final LessonResourceValidator resourceValidator;

    public LessonService(ClassGroupRepository classes,
                         ClassSessionRepository sessions,
                         ClassStudentRepository rosters,
                         ClassTeacherRepository teacherRosters,
                         LessonRepository lessons,
                         LessonResourceRepository resources,
                         LessonCompletionRepository completions,
                         LessonResourceValidator resourceValidator) {
        this.classes = classes;
        this.sessions = sessions;
        this.rosters = rosters;
        this.teacherRosters = teacherRosters;
        this.lessons = lessons;
        this.resources = resources;
        this.completions = completions;
        this.resourceValidator = resourceValidator;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Admin list of lessons for a class. {@code includeArchived=false}
     * hides soft-deleted rows (the "Active" tab); {@code true} includes
     * them (the "All" tab). Each row carries a read tally computed
     * against the current roster so the list view is self-sufficient.
     */
    @Transactional(readOnly = true)
    public List<LessonSummary> list(AuthenticatedUser principal, UUID classId, boolean includeArchived) {
        authoriseClass(principal, classId);

        List<Lesson> rows = includeArchived
                ? lessons.findByClassIdOrderByCreatedAtDesc(
                        classId, org.springframework.data.domain.Pageable.unpaged()).getContent()
                : lessons.findByClassIdInAndArchivedAtIsNullOrderByCreatedAtDesc(List.of(classId));

        if (rows.isEmpty()) return List.of();

        int rosterSize = rosters.findStudents(classId).size();

        List<UUID> lessonIds = new ArrayList<>(rows.size());
        for (Lesson l : rows) lessonIds.add(l.getId());

        // One batched fetch for all completion rows across the returned lesson ids.
        Map<UUID, Integer> readCountByLesson = new HashMap<>();
        for (LessonCompletion c : completions.findByLessonIdIn(lessonIds)) {
            if (c.getStatus() == LessonCompletionStatus.READ) {
                readCountByLesson.merge(c.getLessonId(), 1, Integer::sum);
            }
        }

        // Per-lesson block counts. Fetch only the lesson ids (one row per
        // block) and tally in-memory — resource payloads can be heavy
        // (PROSE markdown in particular), so projecting the id keeps this
        // query cheap even on a long list.
        Map<UUID, Integer> blockCountByLesson = new HashMap<>();
        for (UUID lid : resources.findLessonIdsByLessonIdIn(lessonIds)) {
            blockCountByLesson.merge(lid, 1, Integer::sum);
        }

        // Denormalise session start for every lesson that has a linked
        // session. Fetched in one batch — rows without a sessionId are
        // skipped, and rows whose linked session was deleted get a null
        // start (the frontend will fall back to "no session anchor").
        Map<UUID, Instant> sessionStartById = new HashMap<>();
        List<UUID> sessionIds = new ArrayList<>();
        for (Lesson l : rows) {
            if (l.getSessionId() != null) sessionIds.add(l.getSessionId());
        }
        if (!sessionIds.isEmpty()) {
            for (ClassSession s : sessions.findAllById(sessionIds)) {
                sessionStartById.put(s.getId(), s.getStartsAt());
            }
        }

        List<LessonSummary> out = new ArrayList<>(rows.size());
        for (Lesson l : rows) {
            Instant sessionStartsAt = l.getSessionId() != null
                    ? sessionStartById.get(l.getSessionId())
                    : null;
            out.add(new LessonSummary(
                    l.getId(),
                    l.getClassId(),
                    l.getSessionId(),
                    sessionStartsAt,
                    l.getTitle(),
                    l.getInstructions(),
                    l.getCreatedAt(),
                    l.getArchivedAt(),
                    rosterSize,
                    readCountByLesson.getOrDefault(l.getId(), 0),
                    blockCountByLesson.getOrDefault(l.getId(), 0)
            ));
        }
        return out;
    }

    /**
     * Full admin detail: roster-aware completion sheet plus the ordered
     * list of authored content blocks. Students on the roster appear in
     * roster order (null status if unmarked); students with a persisted
     * completion who have since left the roster are appended to the tail
     * so historical reads remain visible.
     */
    @Transactional(readOnly = true)
    public LessonDetail detail(AuthenticatedUser principal, UUID classId, UUID lessonId) {
        authoriseClass(principal, classId);
        Lesson l = lessons.findByIdAndClassId(lessonId, classId)
                .orElseThrow(() -> AppException.notFound("Lesson"));

        List<ClassRosterMember> roster = rosters.findStudents(classId);
        List<LessonCompletion> marks = completions.findByLessonId(lessonId);

        Map<UUID, LessonCompletion> byStudent = new HashMap<>(marks.size());
        for (LessonCompletion c : marks) byStudent.put(c.getStudentUserId(), c);

        Map<UUID, LessonCompletionView> ordered = new LinkedHashMap<>(roster.size());
        for (ClassRosterMember m : roster) {
            LessonCompletion c = byStudent.get(m.userId());
            ordered.put(m.userId(), toCompletionView(m.userId(), m.displayName(), c));
        }
        // Historical rows from students no longer on the roster.
        for (LessonCompletion c : marks) {
            if (!ordered.containsKey(c.getStudentUserId())) {
                ordered.put(c.getStudentUserId(), toCompletionView(c.getStudentUserId(), null, c));
            }
        }

        List<LessonResource> blocks = resources.findByLessonIdOrderByPositionAsc(lessonId);
        List<LessonResourceView> blockViews = new ArrayList<>(blocks.size());
        for (LessonResource r : blocks) {
            blockViews.add(new LessonResourceView(
                    r.getId(),
                    r.getPosition(),
                    r.getKind(),
                    r.getTitle(),
                    r.getPayload()
            ));
        }

        return new LessonDetail(
                l.getId(),
                l.getClassId(),
                l.getSessionId(),
                l.getTitle(),
                l.getInstructions(),
                l.getCreatedBy(),
                l.getCreatedAt(),
                l.getArchivedAt(),
                new ArrayList<>(ordered.values()),
                blockViews
        );
    }

    // ------------------------------------------------------------------
    // Student self-service reads
    // ------------------------------------------------------------------

    /**
     * All active lessons across the classes the caller is currently
     * enrolled in, merged with the caller's own completion state.
     * Mirrors {@link HomeworkService#listForStudent}. A student with no
     * class enrolments gets an empty list.
     *
     * <p>Ordering mirrors the admin list (newest first) so the mobile /
     * web UI can render a shared "most recent lessons" feed.
     */
    @Transactional(readOnly = true)
    public List<StudentLessonView> listForStudent(AuthenticatedUser principal) {
        requireStudent(principal);
        return buildStudentRollup(principal.userId());
    }

    /**
     * Single-lesson detail view for the calling student. Returns the
     * lesson, the caller's own completion status, and the ordered
     * content blocks.
     *
     * <p>Unlike {@link HomeworkService#detailForStudent}, block payloads
     * are <b>not</b> scrubbed on the wire — MCQ {@code correctIndex}
     * and any other "answer" data ship as-is. Lessons are study
     * material the student is expected to work through interactively;
     * the client mediates when to reveal answers. This is documented
     * on {@link LessonResourceView} so the contract is explicit.
     *
     * <p>Throws {@code 404} for missing / archived / out-of-class
     * lessons — same shape as {@link #updateMyCompletion} to avoid
     * leaking the existence of lessons in classes the student isn't
     * enrolled in.
     */
    @Transactional(readOnly = true)
    public StudentLessonDetail detailForStudent(AuthenticatedUser principal, UUID lessonId) {
        requireStudent(principal);

        Lesson l = lessons.findById(lessonId)
                .orElseThrow(() -> AppException.notFound("Lesson"));
        if (l.getArchivedAt() != null) {
            throw AppException.notFound("Lesson");
        }
        if (!rosters.existsByIdClassIdAndIdStudentUserId(l.getClassId(), principal.userId())) {
            throw AppException.notFound("Lesson");
        }

        ClassGroup clazz = classes.findById(l.getClassId())
                .orElseThrow(() -> AppException.notFound("Class"));
        LessonCompletion mark = completions
                .findByLessonIdAndStudentUserId(lessonId, principal.userId())
                .orElse(null);

        Instant sessionStartsAt = null;
        if (l.getSessionId() != null) {
            sessionStartsAt = sessions.findById(l.getSessionId())
                    .map(ClassSession::getStartsAt)
                    .orElse(null);
        }

        List<LessonResource> blocks = resources.findByLessonIdOrderByPositionAsc(lessonId);
        List<LessonResourceView> blockViews = new ArrayList<>(blocks.size());
        for (LessonResource r : blocks) {
            blockViews.add(new LessonResourceView(
                    r.getId(),
                    r.getPosition(),
                    r.getKind(),
                    r.getTitle(),
                    r.getPayload()
            ));
        }

        return new StudentLessonDetail(
                l.getId(),
                l.getClassId(),
                clazz.getName(),
                l.getSessionId(),
                sessionStartsAt,
                l.getTitle(),
                l.getInstructions(),
                l.getCreatedAt(),
                mark == null ? null : mark.getStatus(),
                mark == null ? null : mark.getMarkedAt(),
                blockViews
        );
    }

    // ------------------------------------------------------------------
    // Student self-service writes
    // ------------------------------------------------------------------

    /**
     * Student self-mark of their own lesson completion. Callers may
     * only touch their own completion on a lesson whose class they're
     * enrolled in. A null status clears (deletes) the row back to the
     * implicit "not started" state.
     *
     * <p>Unlike the homework equivalent, explicit
     * {@link LessonCompletionStatus#NOT_STARTED} is accepted — it
     * preserves a "opened-but-didn't-finish" trail that the implicit
     * null state would erase. See {@link LessonCompletionStatus} for
     * the rationale.
     *
     * <p>Writing {@link LessonCompletionStatus#READ} stamps
     * {@code markedAt} to {@link Instant#now()}; writing
     * {@code NOT_STARTED} still refreshes {@code markedAt} so the
     * admin sheet can display "last touched at" consistently.
     */
    @Transactional
    public StudentLessonView updateMyCompletion(AuthenticatedUser principal,
                                                UUID lessonId,
                                                UpdateMyCompletionRequest req) {
        requireStudent(principal);

        Lesson l = lessons.findById(lessonId)
                .orElseThrow(() -> AppException.notFound("Lesson"));
        if (l.getArchivedAt() != null) {
            // Students shouldn't see archived lessons and certainly can't
            // mark them — return the same shape they'd get for a missing
            // row so we don't leak the existence of soft-deleted lessons.
            throw AppException.notFound("Lesson");
        }
        if (!rosters.existsByIdClassIdAndIdStudentUserId(l.getClassId(), principal.userId())) {
            // Same shape as the not-found branch: the student has no
            // business learning that this lesson exists in some other
            // class.
            throw AppException.notFound("Lesson");
        }

        if (req == null || req.status() == null) {
            completions.deleteByLessonAndStudent(lessonId, principal.userId());
        } else {
            LessonCompletion c = completions
                    .findByLessonIdAndStudentUserId(lessonId, principal.userId())
                    .orElseGet(() -> {
                        LessonCompletion fresh = new LessonCompletion();
                        fresh.setId(UUID.randomUUID());
                        fresh.setLessonId(lessonId);
                        fresh.setStudentUserId(principal.userId());
                        return fresh;
                    });
            c.setStatus(req.status());
            c.setMarkedAt(Instant.now());
            completions.save(c);
        }

        // Return the single-row view, not the whole list — the client
        // just updated one item and wants to reconcile it. We re-fetch
        // the same derived fields the list endpoint uses so the row
        // stays self-consistent.
        ClassGroup clazz = classes.findById(l.getClassId())
                .orElseThrow(() -> AppException.notFound("Class"));
        LessonCompletion mark = completions
                .findByLessonIdAndStudentUserId(lessonId, principal.userId())
                .orElse(null);
        Instant sessionStartsAt = null;
        if (l.getSessionId() != null) {
            sessionStartsAt = sessions.findById(l.getSessionId())
                    .map(ClassSession::getStartsAt)
                    .orElse(null);
        }
        int blockCount = resources.findByLessonIdOrderByPositionAsc(lessonId).size();
        return toStudentView(l, clazz.getName(), sessionStartsAt, mark, blockCount);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public LessonDetail create(AuthenticatedUser principal, UUID classId, CreateLessonRequest req) {
        ClassGroup clazz = authoriseClass(principal, classId);

        String title = requireTitle(req.title());
        UUID sessionId = validateOptionalSession(classId, req.sessionId());

        Lesson l = new Lesson();
        l.setId(UUID.randomUUID());
        l.setClassId(clazz.getId());
        l.setSessionId(sessionId);
        l.setTitle(title);
        l.setInstructions(emptyToNull(req.instructions()));
        l.setCreatedBy(principal.userId());
        lessons.save(l);

        // Validate + persist any authored content blocks. Null / empty is
        // allowed (instructions-only lesson is still valid in v1).
        if (req.resources() != null && !req.resources().isEmpty()) {
            persistResources(l.getId(), req.resources());
        }

        return detail(principal, classId, l.getId());
    }

    @Transactional
    public LessonDetail update(AuthenticatedUser principal,
                               UUID classId,
                               UUID lessonId,
                               UpdateLessonRequest req) {
        authoriseClass(principal, classId);
        Lesson l = lessons.findByIdAndClassId(lessonId, classId)
                .orElseThrow(() -> AppException.notFound("Lesson"));

        if (l.getArchivedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT, "Cannot edit an archived lesson");
        }

        if (req.title() != null) {
            l.setTitle(requireTitle(req.title()));
        }
        if (req.instructions() != null) {
            l.setInstructions(emptyToNull(req.instructions()));
        }
        if (Boolean.TRUE.equals(req.clearSession())) {
            l.setSessionId(null);
        } else if (req.sessionId() != null) {
            l.setSessionId(validateOptionalSession(classId, req.sessionId()));
        }
        lessons.save(l);

        // Resources use replace-on-write: a non-null list means "delete
        // all and insert this new ordered list". A null list means "leave
        // alone". An empty list means "clear all blocks".
        if (req.resources() != null) {
            resources.deleteAllByLessonId(l.getId());
            if (!req.resources().isEmpty()) {
                persistResources(l.getId(), req.resources());
            }
        }

        return detail(principal, classId, l.getId());
    }

    /**
     * Soft-delete: sets {@code archivedAt} on the lesson. Completions
     * are preserved.
     */
    @Transactional
    public void archive(AuthenticatedUser principal, UUID classId, UUID lessonId) {
        authoriseClass(principal, classId);
        Lesson l = lessons.findByIdAndClassId(lessonId, classId)
                .orElseThrow(() -> AppException.notFound("Lesson"));
        if (l.getArchivedAt() == null) {
            l.setArchivedAt(Instant.now());
            lessons.save(l);
        }
    }

    // ------------------------------------------------------------------
    // Authorisation + helpers
    // ------------------------------------------------------------------

    /**
     * Load the class and confirm that the calling principal may act on
     * it. Returns the {@link ClassGroup} for convenience.
     */
    private ClassGroup authoriseClass(AuthenticatedUser principal, UUID classId) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
        ClassGroup clazz = classes.findById(classId)
                .orElseThrow(() -> AppException.notFound("Class"));

        boolean platformAdmin = principal.platformRole() == PlatformRole.PLATFORM_ADMIN;
        if (!platformAdmin) {
            if (principal.schoolId() == null
                    || !clazz.getSchoolId().equals(principal.schoolId())) {
                throw AppException.notFound("Class");
            }
            Role role = principal.role();
            if (role == Role.SCHOOL_ADMIN) {
                // ok
            } else if (role == Role.TEACHER) {
                boolean assigned = teacherRosters.existsByIdClassIdAndIdTeacherUserId(
                        classId, principal.userId());
                if (!assigned) {
                    throw AppException.forbidden("Teachers may only manage lessons for their own classes");
                }
            } else {
                throw AppException.forbidden("Not allowed to manage lessons");
            }
        }
        return clazz;
    }

    /** Confirm an optional session belongs to this class (if provided). */
    private UUID validateOptionalSession(UUID classId, UUID sessionId) {
        if (sessionId == null) return null;
        ClassSession s = sessions.findById(sessionId)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST,
                        "Session does not exist"));
        if (!s.getClassId().equals(classId)) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Session belongs to a different class");
        }
        return sessionId;
    }

    private static String requireTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Title is required");
        }
        if (t.length() > MAX_TITLE_LEN) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Title is too long");
        }
        return t;
    }

    private static LessonCompletionView toCompletionView(UUID studentId,
                                                         String displayName,
                                                         LessonCompletion c) {
        if (c == null) {
            return new LessonCompletionView(studentId, displayName, null, null);
        }
        return new LessonCompletionView(
                studentId,
                displayName,
                c.getStatus(),
                c.getMarkedAt()
        );
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Shared student-rollup builder — called by the student self view
     * (and, if/when the parent flow is wired up for lessons, by the
     * parent-of-child view). Kept package-private for ease of testing
     * and to advertise the "this is the canonical roll-up shape" intent.
     *
     * <p>Batching: one roster lookup, one active-lessons query per set
     * of class ids, one completion fetch keyed on
     * {@code (studentUserId, lessonIds)}, one block-count projection,
     * and one session-start denormalisation. Total round-trips stay
     * constant regardless of how many lessons the student sees.
     */
    private List<StudentLessonView> buildStudentRollup(UUID studentUserId) {
        List<ClassStudent> enrolments = rosters.findByIdStudentUserId(studentUserId);
        if (enrolments.isEmpty()) return List.of();

        List<UUID> classIds = new ArrayList<>(enrolments.size());
        for (ClassStudent cs : enrolments) classIds.add(cs.getId().getClassId());

        List<Lesson> active = lessons
                .findByClassIdInAndArchivedAtIsNullOrderByCreatedAtDesc(classIds);
        if (active.isEmpty()) return List.of();

        // Batch-load class names so we don't N+1 in the mapping loop.
        Map<UUID, String> classNames = new HashMap<>(classIds.size());
        for (ClassGroup c : classes.findAllById(classIds)) {
            classNames.put(c.getId(), c.getName());
        }

        // Single-student completion fetch across every visible lesson.
        List<UUID> lessonIds = new ArrayList<>(active.size());
        for (Lesson l : active) lessonIds.add(l.getId());
        Map<UUID, LessonCompletion> markByLesson = new HashMap<>();
        for (LessonCompletion c : completions
                .findByStudentUserIdAndLessonIdIn(studentUserId, lessonIds)) {
            markByLesson.put(c.getLessonId(), c);
        }

        // Per-lesson block counts. Same trick as the admin list — project
        // lesson ids only so heavy payloads (PROSE markdown especially)
        // don't come over the wire just to compute a count.
        Map<UUID, Integer> blockCountByLesson = new HashMap<>();
        for (UUID lid : resources.findLessonIdsByLessonIdIn(lessonIds)) {
            blockCountByLesson.merge(lid, 1, Integer::sum);
        }

        // Session anchor denormalisation — batch over every lesson that
        // has one. Rows without a sessionId are skipped; rows whose
        // linked session was deleted get a null anchor (the frontend
        // falls back to "no session").
        Map<UUID, Instant> sessionStartById = new HashMap<>();
        List<UUID> sessionIds = new ArrayList<>();
        for (Lesson l : active) {
            if (l.getSessionId() != null) sessionIds.add(l.getSessionId());
        }
        if (!sessionIds.isEmpty()) {
            for (ClassSession s : sessions.findAllById(sessionIds)) {
                sessionStartById.put(s.getId(), s.getStartsAt());
            }
        }

        List<StudentLessonView> out = new ArrayList<>(active.size());
        for (Lesson l : active) {
            Instant sessionStartsAt = l.getSessionId() != null
                    ? sessionStartById.get(l.getSessionId())
                    : null;
            int blockCount = blockCountByLesson.getOrDefault(l.getId(), 0);
            out.add(toStudentView(
                    l,
                    classNames.get(l.getClassId()),
                    sessionStartsAt,
                    markByLesson.get(l.getId()),
                    blockCount
            ));
        }
        return out;
    }

    private static StudentLessonView toStudentView(Lesson l,
                                                   String className,
                                                   Instant sessionStartsAt,
                                                   LessonCompletion c,
                                                   int blockCount) {
        return new StudentLessonView(
                l.getId(),
                l.getClassId(),
                className,
                l.getSessionId(),
                sessionStartsAt,
                l.getTitle(),
                l.getInstructions(),
                l.getCreatedAt(),
                c == null ? null : c.getStatus(),
                c == null ? null : c.getMarkedAt(),
                blockCount
        );
    }

    private static void requireStudent(AuthenticatedUser principal) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
        if (principal.role() != Role.STUDENT) {
            throw AppException.forbidden("Only students can use this endpoint");
        }
    }

    /**
     * Validate + persist an ordered list of resource inputs for one
     * lesson. Position is assigned from the list index — the client
     * doesn't send it. The validator throws on illegal content; we just
     * translate each input into an entity and save.
     */
    private void persistResources(UUID lessonId, List<LessonResourceInput> inputs) {
        List<LessonResource> rows = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            LessonResourceInput in = inputs.get(i);
            if (in == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Resource at index " + i + " is null");
            }
            String kind = resourceValidator.normaliseKind(in.kind());
            if (kind == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Resource at index " + i + " has no kind");
            }
            JsonNode normalised = resourceValidator.validate(kind, in.payload());

            LessonResource r = new LessonResource();
            r.setId(UUID.randomUUID());
            r.setLessonId(lessonId);
            r.setPosition(i);
            r.setKind(kind);
            r.setTitle(emptyToNull(in.title()));
            r.setPayload(normalised);
            rows.add(r);
        }
        resources.saveAll(rows);
    }
}
