package com.chessedu.platform.classes;

import com.chessedu.platform.classes.chess.HomeworkResourceValidator;
import com.chessedu.platform.classes.dto.BlockAttemptView;
import com.chessedu.platform.classes.dto.CreateHomeworkRequest;
import com.chessedu.platform.classes.dto.HomeworkDetail;
import com.chessedu.platform.classes.dto.HomeworkResourceInput;
import com.chessedu.platform.classes.dto.HomeworkResourceView;
import com.chessedu.platform.classes.dto.HomeworkSubmissionView;
import com.chessedu.platform.classes.dto.HomeworkSummary;
import com.chessedu.platform.classes.dto.ClassRosterMember;
import com.chessedu.platform.classes.dto.StudentHomeworkDetail;
import com.chessedu.platform.classes.dto.StudentHomeworkView;
import com.chessedu.platform.classes.dto.UpdateHomeworkRequest;
import com.chessedu.platform.classes.dto.UpdateHomeworkSubmissionRequest;
import com.chessedu.platform.classes.dto.UpdateMySubmissionRequest;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.ClassSession;
import com.chessedu.platform.classes.model.ClassStudent;
import com.chessedu.platform.classes.model.Homework;
import com.chessedu.platform.classes.model.HomeworkBlockAttempt;
import com.chessedu.platform.classes.model.HomeworkResource;
import com.chessedu.platform.classes.model.HomeworkSubmission;
import com.chessedu.platform.classes.model.HomeworkSubmissionStatus;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassSessionRepository;
import com.chessedu.platform.classes.repo.ClassStudentRepository;
import com.chessedu.platform.classes.repo.ClassTeacherRepository;
import com.chessedu.platform.classes.repo.HomeworkBlockAttemptRepository;
import com.chessedu.platform.classes.repo.HomeworkRepository;
import com.chessedu.platform.classes.repo.HomeworkResourceRepository;
import com.chessedu.platform.classes.repo.HomeworkSubmissionRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.ParentLink;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.repo.ParentLinkRepository;
import com.chessedu.platform.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Read/write homework and per-student submission state for a single class.
 *
 * <p>Authorisation model (enforced here, same shape as {@link AttendanceService}):
 * <ul>
 *   <li><b>PLATFORM_ADMIN</b> may read/write any class in any school.</li>
 *   <li><b>SCHOOL_ADMIN</b> may read/write any class in their own school.</li>
 *   <li><b>TEACHER</b> may read/write only classes they're assigned to.</li>
 *   <li>Everyone else is forbidden from these admin-side endpoints.</li>
 * </ul>
 *
 * <p>A homework with {@code archivedAt != null} is hidden from the default
 * admin list (the "Active" tab) but is kept around for historical reports.
 * Teachers cannot hard-delete an assignment — the audit trail is more
 * valuable than the mess of a dangling submission.
 */
@Service
public class HomeworkService {

    private static final int MAX_TITLE_LEN = 200;

    private final ClassGroupRepository classes;
    private final ClassSessionRepository sessions;
    private final ClassStudentRepository rosters;
    private final ClassTeacherRepository teacherRosters;
    private final HomeworkRepository homework;
    private final HomeworkResourceRepository resources;
    private final HomeworkSubmissionRepository submissions;
    private final HomeworkBlockAttemptRepository blockAttempts;
    private final ParentLinkRepository parentLinks;
    private final HomeworkResourceValidator resourceValidator;

    public HomeworkService(ClassGroupRepository classes,
                           ClassSessionRepository sessions,
                           ClassStudentRepository rosters,
                           ClassTeacherRepository teacherRosters,
                           HomeworkRepository homework,
                           HomeworkResourceRepository resources,
                           HomeworkSubmissionRepository submissions,
                           HomeworkBlockAttemptRepository blockAttempts,
                           ParentLinkRepository parentLinks,
                           HomeworkResourceValidator resourceValidator) {
        this.classes = classes;
        this.sessions = sessions;
        this.rosters = rosters;
        this.teacherRosters = teacherRosters;
        this.homework = homework;
        this.resources = resources;
        this.submissions = submissions;
        this.blockAttempts = blockAttempts;
        this.parentLinks = parentLinks;
        this.resourceValidator = resourceValidator;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Admin list of homework for a class. {@code includeArchived=false} hides
     * soft-deleted rows (the "Active" tab); {@code true} includes them (the
     * "All" tab). Each row carries a done/skipped tally computed against
     * the current roster so the list view is self-sufficient.
     */
    @Transactional(readOnly = true)
    public List<HomeworkSummary> list(AuthenticatedUser principal, UUID classId, boolean includeArchived) {
        authoriseClass(principal, classId);

        List<Homework> rows = includeArchived
                ? homework.findByClassIdOrderByCreatedAtDesc(
                        classId, org.springframework.data.domain.Pageable.unpaged()).getContent()
                : homework.findByClassIdInAndArchivedAtIsNullOrderByCreatedAtDesc(List.of(classId));

        if (rows.isEmpty()) return List.of();

        int rosterSize = rosters.findStudents(classId).size();

        // One batched fetch for all submissions across the returned homework ids.
        List<UUID> hwIds = new ArrayList<>(rows.size());
        for (Homework h : rows) hwIds.add(h.getId());
        Map<UUID, int[]> tallies = tallyByHomework(submissions.findByHomeworkIdIn(hwIds));

        // Denormalise session start for every homework that has a linked
        // session. Fetched in one batch — rows without a sessionId are
        // skipped, and rows whose linked session was deleted simply get a
        // null start (the frontend will fall back to "no session anchor").
        Map<UUID, Instant> sessionStartById = new HashMap<>();
        List<UUID> sessionIds = new ArrayList<>();
        for (Homework h : rows) {
            if (h.getSessionId() != null) sessionIds.add(h.getSessionId());
        }
        if (!sessionIds.isEmpty()) {
            for (ClassSession s : sessions.findAllById(sessionIds)) {
                sessionStartById.put(s.getId(), s.getStartsAt());
            }
        }

        List<HomeworkSummary> out = new ArrayList<>(rows.size());
        for (Homework h : rows) {
            int[] t = tallies.getOrDefault(h.getId(), new int[]{0, 0});
            Instant sessionStartsAt = h.getSessionId() != null
                    ? sessionStartById.get(h.getSessionId())
                    : null;
            out.add(new HomeworkSummary(
                    h.getId(),
                    h.getClassId(),
                    h.getSessionId(),
                    sessionStartsAt,
                    h.getTitle(),
                    h.getInstructions(),
                    h.getDueAt(),
                    h.getCreatedAt(),
                    h.getArchivedAt(),
                    rosterSize,
                    t[0],
                    t[1]
            ));
        }
        return out;
    }

    /**
     * Full admin detail: roster-aware submission sheet. Students on the
     * roster appear in roster order (null status if unmarked); students with
     * a persisted submission who have since left the roster are appended to
     * the tail.
     */
    @Transactional(readOnly = true)
    public HomeworkDetail detail(AuthenticatedUser principal, UUID classId, UUID homeworkId) {
        authoriseClass(principal, classId);
        Homework h = homework.findByIdAndClassId(homeworkId, classId)
                .orElseThrow(() -> AppException.notFound("Homework"));

        List<ClassRosterMember> roster = rosters.findStudents(classId);
        List<HomeworkSubmission> marks = submissions.findByHomeworkId(homeworkId);

        Map<UUID, HomeworkSubmission> byStudent = new HashMap<>(marks.size());
        for (HomeworkSubmission s : marks) byStudent.put(s.getStudentUserId(), s);

        Map<UUID, HomeworkSubmissionView> ordered = new LinkedHashMap<>(roster.size());
        for (ClassRosterMember m : roster) {
            HomeworkSubmission s = byStudent.get(m.userId());
            ordered.put(m.userId(), toView(m.userId(), m.displayName(), s));
        }
        // Historical rows from students no longer on the roster.
        for (HomeworkSubmission s : marks) {
            if (!ordered.containsKey(s.getStudentUserId())) {
                ordered.put(s.getStudentUserId(), toView(s.getStudentUserId(), null, s));
            }
        }

        List<HomeworkResource> blocks = resources.findByHomeworkIdOrderByPositionAsc(homeworkId);
        List<HomeworkResourceView> blockViews = new ArrayList<>(blocks.size());
        for (HomeworkResource r : blocks) {
            blockViews.add(new HomeworkResourceView(
                    r.getId(),
                    r.getPosition(),
                    r.getKind(),
                    r.getTitle(),
                    r.getPayload()
            ));
        }

        // Live block-level attempts for every student. The admin sheet groups
        // them by studentUserId to colour the block strip; kept flat here so
        // we don't N+1 through the submission map.
        List<HomeworkBlockAttempt> liveAttempts = blockAttempts.findCurrentByHomeworkId(homeworkId);
        List<BlockAttemptView> attemptViews = new ArrayList<>(liveAttempts.size());
        for (HomeworkBlockAttempt a : liveAttempts) {
            attemptViews.add(toBlockAttemptView(a));
        }

        return new HomeworkDetail(
                h.getId(),
                h.getClassId(),
                h.getSessionId(),
                h.getTitle(),
                h.getInstructions(),
                h.getDueAt(),
                h.getCreatedBy(),
                h.getCreatedAt(),
                h.getArchivedAt(),
                new ArrayList<>(ordered.values()),
                blockViews,
                attemptViews
        );
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public HomeworkDetail create(AuthenticatedUser principal, UUID classId, CreateHomeworkRequest req) {
        ClassGroup clazz = authoriseClass(principal, classId);

        String title = requireTitle(req.title());
        UUID sessionId = validateOptionalSession(classId, req.sessionId());

        Homework h = new Homework();
        h.setId(UUID.randomUUID());
        h.setClassId(clazz.getId());
        h.setSessionId(sessionId);
        h.setTitle(title);
        h.setInstructions(emptyToNull(req.instructions()));
        h.setDueAt(req.dueAt());
        h.setCreatedBy(principal.userId());
        homework.save(h);

        // Validate + persist any authored content blocks. Null / empty is
        // allowed (instructions-only homework is still valid in v1).
        if (req.resources() != null && !req.resources().isEmpty()) {
            persistResources(h.getId(), req.resources());
        }

        return detail(principal, classId, h.getId());
    }

    @Transactional
    public HomeworkDetail update(AuthenticatedUser principal,
                                 UUID classId,
                                 UUID homeworkId,
                                 UpdateHomeworkRequest req) {
        authoriseClass(principal, classId);
        Homework h = homework.findByIdAndClassId(homeworkId, classId)
                .orElseThrow(() -> AppException.notFound("Homework"));

        if (h.getArchivedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT, "Cannot edit an archived homework");
        }

        if (req.title() != null) {
            h.setTitle(requireTitle(req.title()));
        }
        if (req.instructions() != null) {
            h.setInstructions(emptyToNull(req.instructions()));
        }
        if (Boolean.TRUE.equals(req.clearDueAt())) {
            h.setDueAt(null);
        } else if (req.dueAt() != null) {
            h.setDueAt(req.dueAt());
        }
        if (Boolean.TRUE.equals(req.clearSession())) {
            h.setSessionId(null);
        } else if (req.sessionId() != null) {
            h.setSessionId(validateOptionalSession(classId, req.sessionId()));
        }
        homework.save(h);

        // Resources use replace-on-write: a non-null list means "delete all
        // and insert this new ordered list". A null list means "leave
        // alone". An empty list means "clear all blocks".
        if (req.resources() != null) {
            resources.deleteAllByHomeworkId(h.getId());
            if (!req.resources().isEmpty()) {
                persistResources(h.getId(), req.resources());
            }
        }

        return detail(principal, classId, h.getId());
    }

    /**
     * Soft-delete: sets {@code archivedAt} on the assignment. Submissions
     * are preserved.
     */
    @Transactional
    public void archive(AuthenticatedUser principal, UUID classId, UUID homeworkId) {
        authoriseClass(principal, classId);
        Homework h = homework.findByIdAndClassId(homeworkId, classId)
                .orElseThrow(() -> AppException.notFound("Homework"));
        if (h.getArchivedAt() == null) {
            h.setArchivedAt(Instant.now());
            homework.save(h);
        }
    }

    /**
     * Teacher / admin override of a single student's submission state.
     * Semantics mirror the attendance update: a null status clears any
     * existing row (returning the student to an unmarked state).
     */
    @Transactional
    public HomeworkDetail updateSubmission(AuthenticatedUser principal,
                                           UUID classId,
                                           UUID homeworkId,
                                           UUID studentUserId,
                                           UpdateHomeworkSubmissionRequest req) {
        authoriseClass(principal, classId);
        Homework h = homework.findByIdAndClassId(homeworkId, classId)
                .orElseThrow(() -> AppException.notFound("Homework"));

        if (h.getArchivedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT, "Cannot mark an archived homework");
        }

        // Student must be on the current roster.
        boolean onRoster = rosters.findStudents(classId).stream()
                .anyMatch(m -> m.userId().equals(studentUserId));
        if (!onRoster) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Student is not on the class roster");
        }

        if (req.status() == null) {
            submissions.deleteByHomeworkAndStudent(homeworkId, studentUserId);
        } else {
            HomeworkSubmission s = submissions
                    .findByHomeworkIdAndStudentUserId(homeworkId, studentUserId)
                    .orElseGet(() -> {
                        HomeworkSubmission fresh = new HomeworkSubmission();
                        fresh.setId(UUID.randomUUID());
                        fresh.setHomeworkId(homeworkId);
                        fresh.setStudentUserId(studentUserId);
                        return fresh;
                    });
            Instant now = Instant.now();
            s.setStatus(req.status());
            s.setStudentNote(emptyToNull(req.studentNote()));
            s.setMarkedBy(principal.userId());
            s.setMarkedAt(now);
            s.setSubmittedAt(req.status() == HomeworkSubmissionStatus.DONE ? now : null);
            submissions.save(s);
        }

        return detail(principal, classId, homeworkId);
    }

    // ------------------------------------------------------------------
    // Student / parent rollups
    // ------------------------------------------------------------------

    /**
     * All active homework across the classes the caller is currently enrolled
     * in, merged with the caller's own submission state. A student with no
     * class enrolments gets an empty list.
     *
     * <p>Ordering mirrors the admin list (newest first) so the mobile / web
     * UI can render a shared "most recent assignments" feed.
     */
    @Transactional(readOnly = true)
    public List<StudentHomeworkView> listForStudent(AuthenticatedUser principal) {
        requireStudent(principal);
        return buildStudentRollup(principal.userId());
    }

    /**
     * Single-homework detail view for the calling student. Returns the
     * assignment, the caller's own submission status, and the ordered content
     * blocks with teacher-only fields (e.g. {@code solution.uci}) stripped
     * server-side so they never reach the wire.
     *
     * <p>Throws {@code 404} for missing / archived / out-of-class homework —
     * same shape as {@link #updateMySubmission} to avoid leaking the
     * existence of homework in classes the student isn't enrolled in.
     */
    @Transactional(readOnly = true)
    public StudentHomeworkDetail detailForStudent(AuthenticatedUser principal, UUID homeworkId) {
        requireStudent(principal);

        Homework h = homework.findById(homeworkId)
                .orElseThrow(() -> AppException.notFound("Homework"));
        if (h.getArchivedAt() != null) {
            throw AppException.notFound("Homework");
        }
        if (!rosters.existsByIdClassIdAndIdStudentUserId(h.getClassId(), principal.userId())) {
            throw AppException.notFound("Homework");
        }

        ClassGroup clazz = classes.findById(h.getClassId())
                .orElseThrow(() -> AppException.notFound("Class"));
        HomeworkSubmission mark = submissions
                .findByHomeworkIdAndStudentUserId(homeworkId, principal.userId())
                .orElse(null);

        // Caller's own live attempts. Fetched first so the block view builder
        // below can check whether each block has a revealed solution (if so,
        // we skip the solution stripping for THAT block so the student's
        // board viewer has the canonical answer to animate).
        List<HomeworkBlockAttempt> myAttempts =
                blockAttempts.findCurrentForStudent(homeworkId, principal.userId());
        java.util.Set<Integer> revealedBlockIndices = new java.util.HashSet<>();
        for (HomeworkBlockAttempt a : myAttempts) {
            if (a.getSolutionRevealedAt() != null) {
                revealedBlockIndices.add(a.getBlockIndex());
            }
        }

        List<HomeworkResource> blocks = resources.findByHomeworkIdOrderByPositionAsc(homeworkId);
        List<HomeworkResourceView> blockViews = new ArrayList<>(blocks.size());
        for (HomeworkResource r : blocks) {
            JsonNode payload = revealedBlockIndices.contains(r.getPosition())
                    ? r.getPayload()
                    : stripSolution(r.getPayload());
            blockViews.add(new HomeworkResourceView(
                    r.getId(),
                    r.getPosition(),
                    r.getKind(),
                    r.getTitle(),
                    payload
            ));
        }

        // Absence of a row for a given block == "Not attempted" per the
        // design — we don't synthesise empty placeholders here, the frontend
        // joins by blockIndex.
        List<BlockAttemptView> attemptViews = new ArrayList<>(myAttempts.size());
        for (HomeworkBlockAttempt a : myAttempts) {
            attemptViews.add(toBlockAttemptView(a));
        }

        return new StudentHomeworkDetail(
                h.getId(),
                h.getClassId(),
                clazz.getName(),
                h.getSessionId(),
                h.getTitle(),
                h.getInstructions(),
                h.getDueAt(),
                h.getCreatedAt(),
                mark == null ? null : mark.getStatus(),
                mark == null ? null : mark.getStudentNote(),
                mark == null ? null : mark.getSubmittedAt(),
                mark == null ? null : mark.getMarkedAt(),
                blockViews,
                attemptViews
        );
    }

    /**
     * Student self-service mark. Callers may only touch their own submission
     * on an assignment belonging to a class they're enrolled in. A null status
     * clears any existing row (back to the implicit "not started" state).
     * Passing {@link HomeworkSubmissionStatus#NOT_STARTED} is rejected — the
     * explicit reset path is "send null" (consistent with attendance).
     */
    @Transactional
    public StudentHomeworkView updateMySubmission(AuthenticatedUser principal,
                                                  UUID homeworkId,
                                                  UpdateMySubmissionRequest req) {
        requireStudent(principal);
        if (req.status() == HomeworkSubmissionStatus.NOT_STARTED) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Send a null status to clear your submission");
        }

        Homework h = homework.findById(homeworkId)
                .orElseThrow(() -> AppException.notFound("Homework"));
        if (h.getArchivedAt() != null) {
            // Students shouldn't see archived homework and certainly can't mark
            // it — return the same shape they'd get for a missing row so we
            // don't leak the existence of soft-deleted rows.
            throw AppException.notFound("Homework");
        }

        // Enrolment check: student must be on this homework's class roster.
        if (!rosters.existsByIdClassIdAndIdStudentUserId(h.getClassId(), principal.userId())) {
            // Same shape as the not-found branch: the student has no business
            // learning that this homework exists in some other class.
            throw AppException.notFound("Homework");
        }

        if (req.status() == null) {
            submissions.deleteByHomeworkAndStudent(homeworkId, principal.userId());
        } else {
            HomeworkSubmission s = submissions
                    .findByHomeworkIdAndStudentUserId(homeworkId, principal.userId())
                    .orElseGet(() -> {
                        HomeworkSubmission fresh = new HomeworkSubmission();
                        fresh.setId(UUID.randomUUID());
                        fresh.setHomeworkId(homeworkId);
                        fresh.setStudentUserId(principal.userId());
                        return fresh;
                    });
            Instant now = Instant.now();
            s.setStatus(req.status());
            s.setStudentNote(emptyToNull(req.studentNote()));
            // Student self-mark: attribute to themselves so the admin view
            // still has a coherent markedBy.
            s.setMarkedBy(principal.userId());
            s.setMarkedAt(now);
            s.setSubmittedAt(req.status() == HomeworkSubmissionStatus.DONE ? now : null);
            submissions.save(s);
        }

        // Return the single row view, not the whole list — the client just
        // updated one item and wants to reconcile it. We re-fetch the same
        // block-level rollup the list endpoint computes so the row stays
        // self-consistent (block tallies are unaffected by self-mark, but
        // the caller still expects the full DTO shape).
        ClassGroup clazz = classes.findById(h.getClassId())
                .orElseThrow(() -> AppException.notFound("Class"));
        HomeworkSubmission mark = submissions
                .findByHomeworkIdAndStudentUserId(homeworkId, principal.userId())
                .orElse(null);
        int blockCount = resources.findByHomeworkIdOrderByPositionAsc(homeworkId).size();
        int[] tally = new int[4];
        for (HomeworkBlockAttempt a : blockAttempts
                .findCurrentForStudent(homeworkId, principal.userId())) {
            switch (a.getStatus()) {
                case SUBMITTED -> tally[0]++;
                case REVIEWED_OK -> tally[1]++;
                case REVIEWED_NEEDS_WORK -> {
                    if (a.getSolutionRevealedAt() != null) tally[3]++;
                    else tally[2]++;
                }
            }
        }
        return toStudentView(h, clazz.getName(), mark,
                blockCount, tally[0], tally[1], tally[2], tally[3]);
    }

    /**
     * Parent rollup of a specific child's homework. The parent must have a
     * {@link ParentLink} to the given child; platform admins are deliberately
     * not allowed through this endpoint (they have richer admin views).
     */
    @Transactional(readOnly = true)
    public List<StudentHomeworkView> listForChild(AuthenticatedUser principal, UUID childUserId) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
        if (principal.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            throw AppException.forbidden("Platform admins cannot use the parent homework endpoint");
        }
        if (principal.role() != Role.PARENT) {
            throw AppException.forbidden("Only parents can view their children's homework");
        }
        ParentLink.ParentLinkId linkId =
                new ParentLink.ParentLinkId(principal.userId(), childUserId);
        if (!parentLinks.existsById(linkId)) {
            throw AppException.notFound("Child link");
        }
        return buildStudentRollup(childUserId);
    }

    /** Shared student-rollup builder — reused by the student self view and
     *  the parent-of-child view so both return the same shape.
     */
    private List<StudentHomeworkView> buildStudentRollup(UUID studentUserId) {
        List<ClassStudent> enrolments = rosters.findByIdStudentUserId(studentUserId);
        if (enrolments.isEmpty()) return List.of();

        List<UUID> classIds = new ArrayList<>(enrolments.size());
        for (ClassStudent cs : enrolments) classIds.add(cs.getId().getClassId());

        List<Homework> active = homework
                .findByClassIdInAndArchivedAtIsNullOrderByCreatedAtDesc(classIds);
        if (active.isEmpty()) return List.of();

        // Batch-load class names so we don't N+1 in the mapping loop.
        Map<UUID, String> classNames = new HashMap<>(classIds.size());
        for (ClassGroup c : classes.findAllById(classIds)) {
            classNames.put(c.getId(), c.getName());
        }

        // Single-student submission fetch across all visible assignments.
        List<UUID> hwIds = new ArrayList<>(active.size());
        for (Homework h : active) hwIds.add(h.getId());
        Map<UUID, HomeworkSubmission> markByHw = new HashMap<>();
        for (HomeworkSubmission s : submissions
                .findByStudentUserIdAndHomeworkIdIn(studentUserId, hwIds)) {
            markByHw.put(s.getHomeworkId(), s);
        }

        // Per-homework block counts. Fetch only the homework ids (one row per
        // block) and tally in-memory — resource payloads can be heavy so
        // selecting just the id keeps the rollup fast even with a long list.
        Map<UUID, Integer> blockCountByHw = new HashMap<>();
        for (UUID hid : resources.findHomeworkIdsByHomeworkIdIn(hwIds)) {
            blockCountByHw.merge(hid, 1, Integer::sum);
        }

        // Per-homework aggregate counts of the caller's live block attempts.
        // One row per (homework, block) because we filter on the "live"
        // (replaced_by_id IS NULL) predicate, so the mapping is already keyed
        // correctly. We derive four disjoint counters — the derived
        // SOLUTION_REVEALED state (NEEDS_WORK with solutionRevealedAt) is
        // called out separately because the student UI treats it as "seen
        // the solution, retry in progress" rather than "unresolved".
        Map<UUID, int[]> attemptTallies = new HashMap<>();
        for (HomeworkBlockAttempt a : blockAttempts
                .findCurrentForStudentAcrossHomework(studentUserId, hwIds)) {
            int[] t = attemptTallies.computeIfAbsent(a.getHomeworkId(), k -> new int[4]);
            // t[0] = awaitingReview, t[1] = reviewedOk,
            // t[2] = needsWork, t[3] = solutionRevealed
            switch (a.getStatus()) {
                case SUBMITTED -> t[0]++;
                case REVIEWED_OK -> t[1]++;
                case REVIEWED_NEEDS_WORK -> {
                    if (a.getSolutionRevealedAt() != null) t[3]++;
                    else t[2]++;
                }
            }
        }

        List<StudentHomeworkView> out = new ArrayList<>(active.size());
        for (Homework h : active) {
            int[] tally = attemptTallies.getOrDefault(h.getId(), new int[4]);
            int blockCount = blockCountByHw.getOrDefault(h.getId(), 0);
            out.add(toStudentView(
                    h,
                    classNames.get(h.getClassId()),
                    markByHw.get(h.getId()),
                    blockCount,
                    tally[0], tally[1], tally[2], tally[3]
            ));
        }
        return out;
    }

    private static StudentHomeworkView toStudentView(Homework h,
                                                     String className,
                                                     HomeworkSubmission s,
                                                     int blockCount,
                                                     int blocksAwaitingReview,
                                                     int blocksReviewedOk,
                                                     int blocksNeedsWork,
                                                     int blocksSolutionRevealed) {
        return new StudentHomeworkView(
                h.getId(),
                h.getClassId(),
                className,
                h.getSessionId(),
                h.getTitle(),
                h.getInstructions(),
                h.getDueAt(),
                h.getCreatedAt(),
                s == null ? null : s.getStatus(),
                s == null ? null : s.getStudentNote(),
                s == null ? null : s.getSubmittedAt(),
                s == null ? null : s.getMarkedBy(),
                s == null ? null : s.getMarkedAt(),
                blockCount,
                blocksAwaitingReview,
                blocksReviewedOk,
                blocksNeedsWork,
                blocksSolutionRevealed
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

    // ------------------------------------------------------------------
    // Authorisation + helpers
    // ------------------------------------------------------------------

    /**
     * Load the class and confirm that the calling principal may act on it.
     * Returns the {@link ClassGroup} for convenience.
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
                    throw AppException.forbidden("Teachers may only manage homework for their own classes");
                }
            } else {
                throw AppException.forbidden("Not allowed to manage homework");
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

    private static Map<UUID, int[]> tallyByHomework(List<HomeworkSubmission> marks) {
        Map<UUID, int[]> by = new HashMap<>();
        for (HomeworkSubmission s : marks) {
            int[] t = by.computeIfAbsent(s.getHomeworkId(), k -> new int[]{0, 0});
            switch (s.getStatus()) {
                case DONE -> t[0]++;
                case SKIPPED -> t[1]++;
                case NOT_STARTED -> {
                    // explicit not-started shouldn't inflate either counter
                }
            }
        }
        return by;
    }

    /**
     * Map a {@link HomeworkBlockAttempt} row to its wire view. Shared by the
     * admin and student detail builders so the shape is identical on both
     * sides of the fence.
     */
    static BlockAttemptView toBlockAttemptView(HomeworkBlockAttempt a) {
        return new BlockAttemptView(
                a.getId(),
                a.getHomeworkId(),
                a.getStudentUserId(),
                a.getBlockIndex(),
                a.getMoves(),
                a.getStatus(),
                a.getTeacherNote(),
                a.getReviewedBy(),
                a.getReviewedAt(),
                a.getSolutionRevealedAt(),
                a.getReplacedById(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }

    private static HomeworkSubmissionView toView(UUID studentId,
                                                 String displayName,
                                                 HomeworkSubmission s) {
        if (s == null) {
            return new HomeworkSubmissionView(studentId, displayName, null, null, null, null, null);
        }
        return new HomeworkSubmissionView(
                studentId,
                displayName,
                s.getStatus(),
                s.getStudentNote(),
                s.getSubmittedAt(),
                s.getMarkedBy(),
                s.getMarkedAt()
        );
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Defensive copy of a stored resource payload with teacher-only fields
     * removed before it reaches a student. In v1 the only such field is
     * {@code solution} on a {@code BOARD} block, but this is the single choke
     * point — add new kinds here when they ship.
     *
     * <p>Safe on any shape: if {@code payload} isn't a JSON object (shouldn't
     * happen past the validator, but still), we return it unchanged rather
     * than throwing. The downstream view just ships whatever we return.
     */
    private static JsonNode stripSolution(JsonNode payload) {
        if (payload == null || !payload.isObject()) return payload;
        ObjectNode copy = ((ObjectNode) payload).deepCopy();
        copy.remove("solution");
        return copy;
    }

    /**
     * Validate + persist an ordered list of resource inputs for one homework.
     * Position is assigned from the list index — the client doesn't send it.
     * The validator throws on illegal content; we just translate each input
     * into an entity and save.
     */
    private void persistResources(UUID homeworkId, List<HomeworkResourceInput> inputs) {
        List<HomeworkResource> rows = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            HomeworkResourceInput in = inputs.get(i);
            if (in == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Resource at index " + i + " is null");
            }
            String kind = in.kind() == null ? "" : in.kind().trim().toUpperCase();
            if (kind.isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Resource at index " + i + " has no kind");
            }
            JsonNode normalised = resourceValidator.validate(kind, in.payload());

            HomeworkResource r = new HomeworkResource();
            r.setId(UUID.randomUUID());
            r.setHomeworkId(homeworkId);
            r.setPosition(i);
            r.setKind(kind);
            r.setTitle(emptyToNull(in.title()));
            r.setPayload(normalised);
            rows.add(r);
        }
        resources.saveAll(rows);
    }
}
