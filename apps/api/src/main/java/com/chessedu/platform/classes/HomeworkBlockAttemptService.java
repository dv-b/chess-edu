package com.chessedu.platform.classes;

import com.chessedu.platform.classes.chess.BlockAttemptValidator;
import com.chessedu.platform.classes.model.BlockAttemptStatus;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.Homework;
import com.chessedu.platform.classes.model.HomeworkBlockAttempt;
import com.chessedu.platform.classes.model.HomeworkResource;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassStudentRepository;
import com.chessedu.platform.classes.repo.ClassTeacherRepository;
import com.chessedu.platform.classes.repo.HomeworkBlockAttemptRepository;
import com.chessedu.platform.classes.repo.HomeworkRepository;
import com.chessedu.platform.classes.repo.HomeworkResourceRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read/write per-block submission attempts — the work unit introduced by
 * Task #28. See {@code docs/design/student-solution-moves.md} for the full
 * lifecycle and {@code V008__homework_block_attempt.sql} for the storage
 * shape.
 *
 * <p>Three public entry points:
 * <ul>
 *   <li>{@link #submitAttempt} — the student plays moves and hits Submit.
 *       Implements the "Overwrite &amp; submit" flow from the mocks: any
 *       prior live attempt is stamped with {@code replaced_by_id} pointing
 *       to the fresh row. History is preserved so the teacher can still
 *       scroll back.</li>
 *   <li>{@link #reviewAttempt} — the teacher marks the attempt OK or
 *       Needs work and optionally leaves a note. Reviewing a replaced
 *       (historical) attempt is rejected — reviews apply to the <em>live</em>
 *       row.</li>
 *   <li>{@link #revealSolution} — the student unlocks the recorded solution.
 *       The timestamp lives alongside the review, so the teacher's
 *       assessment is preserved even after the reveal.</li>
 * </ul>
 *
 * <p>The legacy assignment-level DONE/SKIPPED self-mark in
 * {@link HomeworkService#updateMySubmission} still works — per the design,
 * it becomes a tertiary micro-action once every block is in a terminal
 * state. Nothing in this service cares about it.
 *
 * <p>Authorisation helpers ({@link #authoriseClass}, {@link #requireStudent})
 * intentionally mirror {@link HomeworkService}'s private copies so the two
 * services remain decoupled; a follow-up refactor can pull them into a
 * shared authz component without touching consumer code.
 */
@Service
public class HomeworkBlockAttemptService {

    private static final int MAX_TEACHER_NOTE_LEN = 2_000;

    private final ClassGroupRepository classes;
    private final ClassStudentRepository rosters;
    private final ClassTeacherRepository teacherRosters;
    private final HomeworkRepository homework;
    private final HomeworkResourceRepository resources;
    private final HomeworkBlockAttemptRepository attempts;
    private final BlockAttemptValidator attemptValidator;

    public HomeworkBlockAttemptService(ClassGroupRepository classes,
                                       ClassStudentRepository rosters,
                                       ClassTeacherRepository teacherRosters,
                                       HomeworkRepository homework,
                                       HomeworkResourceRepository resources,
                                       HomeworkBlockAttemptRepository attempts,
                                       BlockAttemptValidator attemptValidator) {
        this.classes = classes;
        this.rosters = rosters;
        this.teacherRosters = teacherRosters;
        this.homework = homework;
        this.resources = resources;
        this.attempts = attempts;
        this.attemptValidator = attemptValidator;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Every live attempt for the calling student on one homework. Used by
     * the student detail view to colour the block strip and decide which
     * blocks still need work.
     */
    @Transactional(readOnly = true)
    public List<HomeworkBlockAttempt> listMyAttempts(AuthenticatedUser principal,
                                                     UUID homeworkId) {
        requireStudent(principal);
        Homework h = loadStudentVisibleHomework(principal, homeworkId);
        return attempts.findCurrentForStudent(h.getId(), principal.userId());
    }

    /**
     * Every live attempt for every student on one homework — the teacher
     * Submissions sheet with the 1-N block strip per student.
     */
    @Transactional(readOnly = true)
    public List<HomeworkBlockAttempt> listAttemptsForAdmin(AuthenticatedUser principal,
                                                           UUID classId,
                                                           UUID homeworkId) {
        authoriseClass(principal, classId);
        Homework h = homework.findByIdAndClassId(homeworkId, classId)
                .orElseThrow(() -> AppException.notFound("Homework"));
        return attempts.findCurrentByHomeworkId(h.getId());
    }

    /**
     * Full history for one (homework, student, block). Oldest first. Lets
     * the teacher audit prior attempts on a specific block.
     */
    @Transactional(readOnly = true)
    public List<HomeworkBlockAttempt> historyForAdmin(AuthenticatedUser principal,
                                                      UUID classId,
                                                      UUID homeworkId,
                                                      UUID studentUserId,
                                                      int blockIndex) {
        authoriseClass(principal, classId);
        // Validate the triple exists before returning anything.
        homework.findByIdAndClassId(homeworkId, classId)
                .orElseThrow(() -> AppException.notFound("Homework"));
        return attempts.findByHomeworkIdAndStudentUserIdAndBlockIndexOrderByCreatedAtAsc(
                homeworkId, studentUserId, blockIndex);
    }

    // ------------------------------------------------------------------
    // Student writes
    // ------------------------------------------------------------------

    /**
     * Submit moves for one block of one homework. Overwrites any prior live
     * attempt in the same chain ({@code replaced_by_id} pattern).
     *
     * <p>Transactional: the old row's {@code replaced_by_id} update and the
     * new insert must commit together, otherwise the partial unique index
     * {@code homework_block_attempt_current_idx} could briefly see two live
     * rows or lose the current row entirely.
     */
    @Transactional
    public HomeworkBlockAttempt submitAttempt(AuthenticatedUser principal,
                                              UUID homeworkId,
                                              int blockIndex,
                                              JsonNode moves) {
        requireStudent(principal);
        Homework h = loadStudentVisibleHomework(principal, homeworkId);
        HomeworkResource block = requireBoardBlock(h.getId(), blockIndex);

        // Validate the student's moves against the block's running position.
        ArrayNode normMoves = attemptValidator.validate(block.getPayload(), moves);

        // Stamp any existing live attempt as "replaced" BEFORE inserting the
        // new one. Both happen in the same transaction so the partial unique
        // index never sees two live rows — we take the old row out of the
        // index (replaced_by_id goes non-null), then insert the fresh row.
        UUID newId = UUID.randomUUID();
        attempts.findCurrentForBlock(h.getId(), principal.userId(), blockIndex)
                .ifPresent(prev -> {
                    prev.setReplacedById(newId);
                    attempts.save(prev);
                });
        // Force the update to hit the DB before the insert. Without this, the
        // same-key insert could race the update inside a single flush and the
        // unique index complains.
        attempts.flush();

        HomeworkBlockAttempt fresh = new HomeworkBlockAttempt();
        fresh.setId(newId);
        fresh.setHomeworkId(h.getId());
        fresh.setStudentUserId(principal.userId());
        fresh.setBlockIndex(blockIndex);
        fresh.setMoves(normMoves);
        fresh.setStatus(BlockAttemptStatus.SUBMITTED);
        // reviewed_* and solution_revealed_at stay null — pristine Submitted row.
        return attempts.save(fresh);
    }

    /**
     * Unlock the canonical solution on a live attempt. Only valid after a
     * {@link BlockAttemptStatus#REVIEWED_NEEDS_WORK} review — the design
     * rule is "reveal is a last-resort after the teacher has seen the work".
     * Idempotent: revealing twice is a no-op (we keep the first timestamp).
     *
     * <p>Does <em>not</em> change {@code status}. The teacher's Needs-work
     * assessment is preserved; the "Solution revealed" pill in the UI is
     * derived from {@code REVIEWED_NEEDS_WORK} + a non-null
     * {@code solution_revealed_at}.
     */
    @Transactional
    public HomeworkBlockAttempt revealSolution(AuthenticatedUser principal,
                                               UUID homeworkId,
                                               int blockIndex) {
        requireStudent(principal);
        Homework h = loadStudentVisibleHomework(principal, homeworkId);
        // Confirm the block is a BOARD (and that a solution was authored) —
        // the service enforces "reveal only when a solution exists" so the
        // client doesn't get a silent no-op on a block the author left
        // unanswered.
        HomeworkResource block = requireBoardBlock(h.getId(), blockIndex);
        if (!payloadHasSolution(block.getPayload())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "No solution is available for this block");
        }

        HomeworkBlockAttempt current = attempts
                .findCurrentForBlock(h.getId(), principal.userId(), blockIndex)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST,
                        "No attempt to reveal the solution for"));

        if (current.getStatus() != BlockAttemptStatus.REVIEWED_NEEDS_WORK) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "The solution can only be revealed after a 'needs work' review");
        }
        if (current.getSolutionRevealedAt() == null) {
            current.setSolutionRevealedAt(Instant.now());
            attempts.save(current);
        }
        return current;
    }

    // ------------------------------------------------------------------
    // Teacher writes
    // ------------------------------------------------------------------

    /**
     * Review a specific attempt. The attempt must be the live row in its
     * chain — reviewing a superseded attempt is rejected so teacher actions
     * always apply to what the student currently sees.
     *
     * @param outcome must be {@link BlockAttemptStatus#REVIEWED_OK} or
     *                {@link BlockAttemptStatus#REVIEWED_NEEDS_WORK};
     *                {@link BlockAttemptStatus#SUBMITTED} is rejected (review
     *                cannot set the student-visible state back to unreviewed).
     */
    @Transactional
    public HomeworkBlockAttempt reviewAttempt(AuthenticatedUser principal,
                                              UUID attemptId,
                                              BlockAttemptStatus outcome,
                                              String teacherNote) {
        if (outcome != BlockAttemptStatus.REVIEWED_OK
                && outcome != BlockAttemptStatus.REVIEWED_NEEDS_WORK) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Review outcome must be REVIEWED_OK or REVIEWED_NEEDS_WORK");
        }

        HomeworkBlockAttempt a = attempts.findById(attemptId)
                .orElseThrow(() -> AppException.notFound("Attempt"));
        if (a.getReplacedById() != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Cannot review a superseded attempt");
        }

        Homework h = homework.findById(a.getHomeworkId())
                .orElseThrow(() -> AppException.notFound("Homework"));
        // Teachers / admins for the class may review. authoriseClass also
        // rejects anonymous / wrong-school / non-assigned teachers.
        authoriseClass(principal, h.getClassId());
        if (h.getArchivedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Cannot review an archived homework");
        }

        String trimmedNote = trimNote(teacherNote);
        Instant now = Instant.now();
        a.setStatus(outcome);
        a.setTeacherNote(trimmedNote);
        a.setReviewedBy(principal.userId());
        a.setReviewedAt(now);
        // If the teacher is flipping from NEEDS_WORK back to OK (or vice
        // versa) we intentionally leave solution_revealed_at alone: once
        // unlocked, the answer stays unlocked for this attempt.
        return attempts.save(a);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Load a homework visible to the calling student (not archived, and the
     * student is on the class roster). Uses the same 404 shape as
     * {@link HomeworkService#detailForStudent} so we don't leak the
     * existence of homework in classes the student isn't enrolled in.
     */
    private Homework loadStudentVisibleHomework(AuthenticatedUser principal, UUID homeworkId) {
        Homework h = homework.findById(homeworkId)
                .orElseThrow(() -> AppException.notFound("Homework"));
        if (h.getArchivedAt() != null) {
            throw AppException.notFound("Homework");
        }
        if (!rosters.existsByIdClassIdAndIdStudentUserId(h.getClassId(), principal.userId())) {
            throw AppException.notFound("Homework");
        }
        return h;
    }

    /** Load the block at the given index and assert it's a BOARD. */
    private HomeworkResource requireBoardBlock(UUID homeworkId, int blockIndex) {
        if (blockIndex < 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Block index cannot be negative");
        }
        List<HomeworkResource> all = resources.findByHomeworkIdOrderByPositionAsc(homeworkId);
        if (blockIndex >= all.size()) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Block index out of range");
        }
        HomeworkResource block = all.get(blockIndex);
        if (!"BOARD".equalsIgnoreCase(block.getKind())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Only BOARD blocks accept submissions in v1");
        }
        return block;
    }

    private static boolean payloadHasSolution(JsonNode payload) {
        if (payload == null || !payload.isObject()) return false;
        JsonNode sol = payload.get("solution");
        if (sol == null || !sol.isObject()) return false;
        JsonNode uci = sol.get("uci");
        return uci != null && uci.isArray() && !uci.isEmpty();
    }

    private static String trimNote(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.length() > MAX_TEACHER_NOTE_LEN) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Teacher note is too long (max " + MAX_TEACHER_NOTE_LEN + " chars)");
        }
        return t;
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
     * Load the class and confirm that the calling principal may act on it.
     * Mirrors {@link HomeworkService#authoriseClass} — kept here as its own
     * copy so the two services remain decoupled.
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
                    throw AppException.forbidden(
                            "Teachers may only review attempts for their own classes");
                }
            } else {
                throw AppException.forbidden("Not allowed to review attempts");
            }
        }
        return clazz;
    }
}
