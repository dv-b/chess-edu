package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.BlockQuestionInboxRow;
import com.chessedu.platform.classes.dto.BlockQuestionReplyView;
import com.chessedu.platform.classes.dto.BlockQuestionThreadView;
import com.chessedu.platform.classes.dto.BlockQuestionView;
import com.chessedu.platform.classes.model.BlockQuestion;
import com.chessedu.platform.classes.model.BlockQuestionParentKind;
import com.chessedu.platform.classes.model.BlockQuestionReply;
import com.chessedu.platform.classes.model.ClassGroup;
import com.chessedu.platform.classes.model.Homework;
import com.chessedu.platform.classes.model.Lesson;
import com.chessedu.platform.classes.repo.BlockQuestionReplyRepository;
import com.chessedu.platform.classes.repo.BlockQuestionRepository;
import com.chessedu.platform.classes.repo.ClassGroupRepository;
import com.chessedu.platform.classes.repo.ClassStudentRepository;
import com.chessedu.platform.classes.repo.ClassTeacherRepository;
import com.chessedu.platform.classes.repo.HomeworkRepository;
import com.chessedu.platform.classes.repo.LessonRepository;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read/write block-level Q&amp;A threads on homework + lesson blocks.
 *
 * <p>Authorisation mirrors {@link HomeworkService} / {@link LessonService}:
 * <ul>
 *   <li>PLATFORM_ADMIN — every class.</li>
 *   <li>SCHOOL_ADMIN — classes in their own school.</li>
 *   <li>TEACHER — only classes they're assigned to.</li>
 *   <li>Anyone else hitting the admin-side endpoints gets 403.</li>
 * </ul>
 *
 * <p>Student endpoints (open thread, reply, list own threads) live in a
 * sibling service that ships with Task #67. Authorisation there is
 * "asker can see / continue their own thread" + "must be enrolled in
 * the parent's class".
 *
 * <p>Inbox semantics:
 * <ul>
 *   <li>Lists only unresolved threads, oldest first.</li>
 *   <li>Filters out threads pinned to archived homework / lesson rows
 *       — a teacher shouldn't see ghost threads on assignments they've
 *       cleaned up. The historical thread is still readable via the
 *       single-thread endpoint if a question id is known.</li>
 * </ul>
 *
 * <p>Resolution is one-way in v1: replying to a resolved thread or
 * resolving an already-resolved thread both return 409 CONFLICT, so
 * the contract stays clean. A future "re-open" feature can lift this.
 */
@Service
public class BlockQuestionService {

    /**
     * Body cap; mirrors the schema CHECK constraint. Validating in the
     * service lets us return {@code VALIDATION_FAILED} with a friendly
     * message instead of the DB's generic constraint error.
     */
    private static final int MAX_BODY_LEN = 2000;

    private final ClassGroupRepository classes;
    private final ClassTeacherRepository teacherRosters;
    private final ClassStudentRepository studentRosters;
    private final HomeworkRepository homework;
    private final LessonRepository lessons;
    private final BlockQuestionRepository questions;
    private final BlockQuestionReplyRepository replies;
    private final UserRepository users;

    public BlockQuestionService(ClassGroupRepository classes,
                                ClassTeacherRepository teacherRosters,
                                ClassStudentRepository studentRosters,
                                HomeworkRepository homework,
                                LessonRepository lessons,
                                BlockQuestionRepository questions,
                                BlockQuestionReplyRepository replies,
                                UserRepository users) {
        this.classes = classes;
        this.teacherRosters = teacherRosters;
        this.studentRosters = studentRosters;
        this.homework = homework;
        this.lessons = lessons;
        this.questions = questions;
        this.replies = replies;
        this.users = users;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Class inbox — every unresolved thread on a non-archived parent in
     * this class, oldest first. Each row carries a thin preview
     * (reply count, last-reply meta) so the UI can render the list
     * without fetching every reply chain.
     */
    @Transactional(readOnly = true)
    public List<BlockQuestionInboxRow> inbox(AuthenticatedUser principal, UUID classId) {
        authoriseClass(principal, classId);

        // Hit the partial index in V011 (block_question_class_open_idx).
        List<BlockQuestion> rows = questions.findByClassIdAndResolvedAtIsNullOrderByCreatedAtAsc(classId);
        if (rows.isEmpty()) return List.of();

        // Filter out questions whose parent is archived. Bulk-load the
        // parents per kind so we hit the DB at most twice regardless of
        // thread count.
        Set<UUID> homeworkIds = new HashSet<>();
        Set<UUID> lessonIds = new HashSet<>();
        for (BlockQuestion q : rows) {
            switch (q.getParentKind()) {
                case HOMEWORK -> homeworkIds.add(q.getParentId());
                case LESSON -> lessonIds.add(q.getParentId());
            }
        }

        Map<UUID, Homework> homeworkById = new HashMap<>();
        if (!homeworkIds.isEmpty()) {
            for (Homework h : homework.findAllById(homeworkIds)) {
                homeworkById.put(h.getId(), h);
            }
        }
        Map<UUID, Lesson> lessonById = new HashMap<>();
        if (!lessonIds.isEmpty()) {
            for (Lesson l : lessons.findAllById(lessonIds)) {
                lessonById.put(l.getId(), l);
            }
        }

        List<BlockQuestion> live = new ArrayList<>(rows.size());
        for (BlockQuestion q : rows) {
            switch (q.getParentKind()) {
                case HOMEWORK -> {
                    Homework h = homeworkById.get(q.getParentId());
                    if (h != null && h.getArchivedAt() == null) live.add(q);
                }
                case LESSON -> {
                    Lesson l = lessonById.get(q.getParentId());
                    if (l != null && l.getArchivedAt() == null) live.add(q);
                }
            }
        }
        if (live.isEmpty()) return List.of();

        // Batch-fetch replies for the surviving questions so we can
        // compute reply counts + last-reply meta without N+1.
        List<UUID> liveIds = live.stream().map(BlockQuestion::getId).toList();
        List<BlockQuestionReply> allReplies = replies.findByQuestionIdInOrderByCreatedAtAsc(liveIds);

        // Group replies by question id. The batch is already sorted
        // oldest-first by the repo method, so we can read tail-of-list
        // for "last reply".
        Map<UUID, List<BlockQuestionReply>> repliesByQ = new HashMap<>();
        for (BlockQuestionReply r : allReplies) {
            repliesByQ.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }

        // Resolve display names for askers + last-reply authors in one
        // batch.
        Set<UUID> userIds = new HashSet<>();
        for (BlockQuestion q : live) userIds.add(q.getStudentUserId());
        for (BlockQuestionReply r : allReplies) userIds.add(r.getAuthorUserId());
        Map<UUID, String> nameById = displayNamesFor(userIds);

        List<BlockQuestionInboxRow> out = new ArrayList<>(live.size());
        for (BlockQuestion q : live) {
            String parentTitle = parentTitleOf(q, homeworkById, lessonById);
            List<BlockQuestionReply> rs = repliesByQ.getOrDefault(q.getId(), List.of());
            int count = rs.size();
            Instant lastReplyAt = count == 0 ? null : rs.get(count - 1).getCreatedAt();
            String lastReplyAuthor = count == 0
                    ? null
                    : nameById.get(rs.get(count - 1).getAuthorUserId());
            out.add(new BlockQuestionInboxRow(
                    toView(q, nameById),
                    parentTitle,
                    count,
                    lastReplyAt,
                    lastReplyAuthor));
        }
        return out;
    }

    /**
     * Per-block overlay — every thread (open + resolved) anchored to
     * one block, oldest first. Mounted on the homework Submissions
     * sheet + lesson detail page so a teacher sees the conversation
     * inline with the block they're reviewing.
     *
     * <p>Verifies the parent belongs to the class so a teacher can't
     * sniff threads on a homework in a different class by passing the
     * right parentId.
     */
    @Transactional(readOnly = true)
    public List<BlockQuestionThreadView> listForBlock(AuthenticatedUser principal,
                                                     UUID classId,
                                                     BlockQuestionParentKind parentKind,
                                                     UUID parentId,
                                                     int blockIndex) {
        authoriseClass(principal, classId);
        verifyParentInClass(parentKind, parentId, classId);

        List<BlockQuestion> rows = questions
                .findByParentKindAndParentIdAndBlockIndexOrderByCreatedAtAsc(parentKind, parentId, blockIndex);
        if (rows.isEmpty()) return List.of();

        List<UUID> ids = rows.stream().map(BlockQuestion::getId).toList();
        List<BlockQuestionReply> allReplies = replies.findByQuestionIdInOrderByCreatedAtAsc(ids);

        Map<UUID, List<BlockQuestionReply>> repliesByQ = new HashMap<>();
        for (BlockQuestionReply r : allReplies) {
            repliesByQ.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }

        Set<UUID> userIds = new HashSet<>();
        for (BlockQuestion q : rows) {
            userIds.add(q.getStudentUserId());
            if (q.getResolvedBy() != null) userIds.add(q.getResolvedBy());
        }
        for (BlockQuestionReply r : allReplies) userIds.add(r.getAuthorUserId());
        Map<UUID, String> nameById = displayNamesFor(userIds);

        String parentTitle = parentTitleOfRaw(parentKind, parentId);

        List<BlockQuestionThreadView> out = new ArrayList<>(rows.size());
        for (BlockQuestion q : rows) {
            List<BlockQuestionReply> rs = repliesByQ.getOrDefault(q.getId(), List.of());
            out.add(new BlockQuestionThreadView(
                    toView(q, nameById),
                    parentTitle,
                    toReplyViews(rs, q.getStudentUserId(), nameById)));
        }
        return out;
    }

    /**
     * Single thread by id. Reachable when the caller has any way to
     * derive a question id (inbox click, deep link, etc.). The class
     * id is passed for symmetry with the rest of the admin URL shape
     * — the service still cross-checks that the question's stored
     * {@code classId} matches.
     */
    @Transactional(readOnly = true)
    public BlockQuestionThreadView getThread(AuthenticatedUser principal,
                                             UUID classId,
                                             UUID questionId) {
        authoriseClass(principal, classId);
        BlockQuestion q = loadInClass(classId, questionId);
        return assembleThread(q);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Post a reply on a thread as the calling teacher / admin. Returns
     * the assembled thread so the UI can repaint without a second
     * fetch. Replying to a resolved thread is rejected with 409 — v1
     * is one-way close.
     */
    @Transactional
    public BlockQuestionThreadView replyAsTeacher(AuthenticatedUser principal,
                                                  UUID classId,
                                                  UUID questionId,
                                                  String body) {
        authoriseClass(principal, classId);
        BlockQuestion q = loadInClass(classId, questionId);

        if (q.getResolvedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Thread is resolved — cannot post a new reply");
        }

        String trimmed = requireBody(body);

        BlockQuestionReply reply = new BlockQuestionReply();
        reply.setId(UUID.randomUUID());
        reply.setQuestionId(q.getId());
        reply.setAuthorUserId(principal.userId());
        reply.setBody(trimmed);
        // createdAt stamped by AuditingEntityListener on @PrePersist.
        replies.save(reply);

        return assembleThread(q);
    }

    /**
     * Stamp the thread as resolved by the calling teacher / admin.
     * Idempotency note: re-resolving an already-resolved thread is
     * rejected with 409 to keep the contract clean (and to surface
     * "you opened a stale view" style races at the UI).
     */
    @Transactional
    public BlockQuestionThreadView resolveThread(AuthenticatedUser principal,
                                                 UUID classId,
                                                 UUID questionId) {
        authoriseClass(principal, classId);
        BlockQuestion q = loadInClass(classId, questionId);

        if (q.getResolvedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Thread is already resolved");
        }

        q.setResolvedAt(Instant.now());
        q.setResolvedBy(principal.userId());
        questions.save(q);

        return assembleThread(q);
    }

    // ------------------------------------------------------------------
    // Student self-service reads
    // ------------------------------------------------------------------

    /**
     * Student-side per-block list: every thread the caller has opened
     * on this specific (parent, block) pair, both open and resolved,
     * oldest-first. The caller's homework / lesson detail page hits
     * this so the "Ask teacher" affordance can render the existing
     * conversation inline if there is one.
     *
     * <p>404 hides existence on:
     * <ul>
     *   <li>archived parent (homework / lesson),</li>
     *   <li>student not enrolled in the parent's class.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<BlockQuestionThreadView> listMyThreadsForBlock(AuthenticatedUser principal,
                                                              BlockQuestionParentKind parentKind,
                                                              UUID parentId,
                                                              int blockIndex) {
        requireStudent(principal);
        loadStudentVisibleParent(principal, parentKind, parentId);

        List<BlockQuestion> rows = questions
                .findByParentKindAndParentIdAndBlockIndexAndStudentUserIdOrderByCreatedAtAsc(
                        parentKind, parentId, blockIndex, principal.userId());
        if (rows.isEmpty()) return List.of();

        List<UUID> ids = rows.stream().map(BlockQuestion::getId).toList();
        List<BlockQuestionReply> allReplies = replies.findByQuestionIdInOrderByCreatedAtAsc(ids);

        Map<UUID, List<BlockQuestionReply>> repliesByQ = new HashMap<>();
        for (BlockQuestionReply r : allReplies) {
            repliesByQ.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }

        Set<UUID> userIds = new HashSet<>();
        for (BlockQuestion q : rows) {
            userIds.add(q.getStudentUserId());
            if (q.getResolvedBy() != null) userIds.add(q.getResolvedBy());
        }
        for (BlockQuestionReply r : allReplies) userIds.add(r.getAuthorUserId());
        Map<UUID, String> nameById = displayNamesFor(userIds);

        String parentTitle = parentTitleOfRaw(parentKind, parentId);

        List<BlockQuestionThreadView> out = new ArrayList<>(rows.size());
        for (BlockQuestion q : rows) {
            List<BlockQuestionReply> rs = repliesByQ.getOrDefault(q.getId(), List.of());
            out.add(new BlockQuestionThreadView(
                    toView(q, nameById),
                    parentTitle,
                    toReplyViews(rs, q.getStudentUserId(), nameById)));
        }
        return out;
    }

    /**
     * Single-thread read by id, ownership-checked. The student got the
     * id from the per-block list or a deep link — either way we only
     * surface threads they opened. 404 hides existence on
     * not-found / not-owned, so a crafted URL doesn't leak information
     * about other students' questions.
     *
     * <p>Note: unlike the per-block list, this DOESN'T re-check the
     * parent's archive status. A student can still read their own
     * thread even after the teacher archives the parent — so the
     * historical record stays available. Same shape the admin
     * single-thread endpoint uses.
     */
    @Transactional(readOnly = true)
    public BlockQuestionThreadView getMyThread(AuthenticatedUser principal, UUID questionId) {
        requireStudent(principal);
        BlockQuestion q = loadOwnedByStudent(principal, questionId);
        return assembleThread(q);
    }

    /**
     * Cross-class rollup — every thread the calling student has
     * opened, across every class they're enrolled in. Ordered
     * newest-first by question {@code createdAt}.
     *
     * <p>Includes both open and resolved threads so the rollup acts as
     * a single "where am I in the conversation?" view. Threads on
     * archived parents stay visible (the historical record remains
     * valuable to the asker even after the teacher archives the
     * homework / lesson — same call as {@link #getMyThread}). Each
     * thread carries its full reply chain so the UI can render a
     * preview without a per-row fetch.
     */
    @Transactional(readOnly = true)
    public List<BlockQuestionThreadView> listAllMyThreads(AuthenticatedUser principal) {
        requireStudent(principal);

        List<BlockQuestion> rows = questions.findByStudentUserIdOrderByCreatedAtDesc(principal.userId());
        if (rows.isEmpty()) return List.of();

        List<UUID> ids = rows.stream().map(BlockQuestion::getId).toList();
        List<BlockQuestionReply> allReplies = replies.findByQuestionIdInOrderByCreatedAtAsc(ids);

        Map<UUID, List<BlockQuestionReply>> repliesByQ = new HashMap<>();
        for (BlockQuestionReply r : allReplies) {
            repliesByQ.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }

        // Resolve display names + parent titles in one batch each.
        Set<UUID> userIds = new HashSet<>();
        for (BlockQuestion q : rows) {
            userIds.add(q.getStudentUserId());
            if (q.getResolvedBy() != null) userIds.add(q.getResolvedBy());
        }
        for (BlockQuestionReply r : allReplies) userIds.add(r.getAuthorUserId());
        Map<UUID, String> nameById = displayNamesFor(userIds);

        Set<UUID> homeworkIds = new HashSet<>();
        Set<UUID> lessonIds = new HashSet<>();
        for (BlockQuestion q : rows) {
            switch (q.getParentKind()) {
                case HOMEWORK -> homeworkIds.add(q.getParentId());
                case LESSON -> lessonIds.add(q.getParentId());
            }
        }
        Map<UUID, Homework> homeworkById = new HashMap<>();
        if (!homeworkIds.isEmpty()) {
            for (Homework h : homework.findAllById(homeworkIds)) {
                homeworkById.put(h.getId(), h);
            }
        }
        Map<UUID, Lesson> lessonById = new HashMap<>();
        if (!lessonIds.isEmpty()) {
            for (Lesson l : lessons.findAllById(lessonIds)) {
                lessonById.put(l.getId(), l);
            }
        }

        List<BlockQuestionThreadView> out = new ArrayList<>(rows.size());
        for (BlockQuestion q : rows) {
            List<BlockQuestionReply> rs = repliesByQ.getOrDefault(q.getId(), List.of());
            String parentTitle = parentTitleOf(q, homeworkById, lessonById);
            out.add(new BlockQuestionThreadView(
                    toView(q, nameById),
                    parentTitle,
                    toReplyViews(rs, q.getStudentUserId(), nameById)));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Student self-service writes
    // ------------------------------------------------------------------

    /**
     * Open a new Q&amp;A thread anchored to a (parent, block) pair.
     * Returns the assembled thread (head + empty replies) so the UI
     * can render the new state without a refetch.
     *
     * <p>Student must be enrolled in the parent's class and the parent
     * must not be archived — both enforced via
     * {@link #loadStudentVisibleParent}, which 404s rather than 403s
     * to hide existence.
     */
    @Transactional
    public BlockQuestionThreadView openThread(AuthenticatedUser principal,
                                              BlockQuestionParentKind parentKind,
                                              UUID parentId,
                                              int blockIndex,
                                              String body) {
        requireStudent(principal);
        UUID classId = loadStudentVisibleParent(principal, parentKind, parentId);

        if (blockIndex < 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Block index cannot be negative");
        }
        String trimmed = requireBody(body);

        BlockQuestion q = new BlockQuestion();
        q.setId(UUID.randomUUID());
        q.setClassId(classId);
        q.setParentKind(parentKind);
        q.setParentId(parentId);
        q.setBlockIndex(blockIndex);
        q.setStudentUserId(principal.userId());
        q.setBody(trimmed);
        // createdAt / updatedAt stamped by AuditingEntityListener.
        questions.save(q);

        return assembleThread(q);
    }

    /**
     * Student appends a reply to their own thread. Same one-way-close
     * semantics the teacher path uses: replying to a resolved thread
     * is 409. To continue a closed conversation, the student opens a
     * fresh thread on the same block.
     */
    @Transactional
    public BlockQuestionThreadView replyAsStudent(AuthenticatedUser principal,
                                                  UUID questionId,
                                                  String body) {
        requireStudent(principal);
        BlockQuestion q = loadOwnedByStudent(principal, questionId);

        if (q.getResolvedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Thread is resolved — open a new thread to continue the conversation");
        }

        String trimmed = requireBody(body);

        BlockQuestionReply reply = new BlockQuestionReply();
        reply.setId(UUID.randomUUID());
        reply.setQuestionId(q.getId());
        reply.setAuthorUserId(principal.userId());
        reply.setBody(trimmed);
        // createdAt stamped by AuditingEntityListener.
        replies.save(reply);

        return assembleThread(q);
    }

    /**
     * "Thanks, I figured it out" — the asker closes their own thread.
     * Same 409 on already-resolved as the teacher path so the
     * contract stays uniform across both surfaces.
     */
    @Transactional
    public BlockQuestionThreadView selfResolve(AuthenticatedUser principal, UUID questionId) {
        requireStudent(principal);
        BlockQuestion q = loadOwnedByStudent(principal, questionId);

        if (q.getResolvedAt() != null) {
            throw new AppException(ErrorCode.CONFLICT, "Thread is already resolved");
        }

        q.setResolvedAt(Instant.now());
        q.setResolvedBy(principal.userId());
        questions.save(q);

        return assembleThread(q);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Lookup-and-cross-check helper used by every single-thread path. */
    private BlockQuestion loadInClass(UUID classId, UUID questionId) {
        BlockQuestion q = questions.findById(questionId)
                .orElseThrow(() -> AppException.notFound("Question"));
        if (!q.getClassId().equals(classId)) {
            // 404 hides existence — same shape as a missing question.
            throw AppException.notFound("Question");
        }
        return q;
    }

    private BlockQuestionThreadView assembleThread(BlockQuestion q) {
        List<BlockQuestionReply> rs = replies.findByQuestionIdOrderByCreatedAtAsc(q.getId());

        Set<UUID> userIds = new HashSet<>();
        userIds.add(q.getStudentUserId());
        if (q.getResolvedBy() != null) userIds.add(q.getResolvedBy());
        for (BlockQuestionReply r : rs) userIds.add(r.getAuthorUserId());
        Map<UUID, String> nameById = displayNamesFor(userIds);

        String parentTitle = parentTitleOfRaw(q.getParentKind(), q.getParentId());

        return new BlockQuestionThreadView(
                toView(q, nameById),
                parentTitle,
                toReplyViews(rs, q.getStudentUserId(), nameById));
    }

    private List<BlockQuestionReplyView> toReplyViews(List<BlockQuestionReply> rs,
                                                     UUID askerUserId,
                                                     Map<UUID, String> nameById) {
        List<BlockQuestionReplyView> out = new ArrayList<>(rs.size());
        for (BlockQuestionReply r : rs) {
            // V1 role heuristic: if the author matches the asker we
            // render as STUDENT, else TEACHER. Good enough for the
            // typical (student opens, teacher replies) case; an admin
            // reply also reads as TEACHER which is fine for the chrome.
            // A snapshotted role column in the schema would let us be
            // more precise — file as a follow-up if it comes up.
            String role = r.getAuthorUserId().equals(askerUserId) ? "STUDENT" : "TEACHER";
            out.add(new BlockQuestionReplyView(
                    r.getId(),
                    r.getQuestionId(),
                    r.getAuthorUserId(),
                    nameById.get(r.getAuthorUserId()),
                    role,
                    r.getBody(),
                    r.getCreatedAt()));
        }
        return out;
    }

    private BlockQuestionView toView(BlockQuestion q, Map<UUID, String> nameById) {
        return new BlockQuestionView(
                q.getId(),
                q.getClassId(),
                q.getParentKind(),
                q.getParentId(),
                q.getBlockIndex(),
                q.getStudentUserId(),
                nameById.get(q.getStudentUserId()),
                q.getBody(),
                q.getResolvedAt(),
                q.getResolvedBy(),
                q.getResolvedBy() == null ? null : nameById.get(q.getResolvedBy()),
                q.getCreatedAt(),
                q.getUpdatedAt());
    }

    private Map<UUID, String> displayNamesFor(Set<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>(userIds.size());
        for (User u : users.findAllById(userIds)) {
            out.put(u.getId(), u.getDisplayName());
        }
        return out;
    }

    private String parentTitleOf(BlockQuestion q,
                                 Map<UUID, Homework> homeworkById,
                                 Map<UUID, Lesson> lessonById) {
        return switch (q.getParentKind()) {
            case HOMEWORK -> {
                Homework h = homeworkById.get(q.getParentId());
                yield h == null ? null : h.getTitle();
            }
            case LESSON -> {
                Lesson l = lessonById.get(q.getParentId());
                yield l == null ? null : l.getTitle();
            }
        };
    }

    /** Single-thread variant — one DB lookup. */
    private String parentTitleOfRaw(BlockQuestionParentKind kind, UUID parentId) {
        return switch (kind) {
            case HOMEWORK -> homework.findById(parentId).map(Homework::getTitle).orElse(null);
            case LESSON -> lessons.findById(parentId).map(Lesson::getTitle).orElse(null);
        };
    }

    /** Confirm the parent row belongs to the given class. */
    private void verifyParentInClass(BlockQuestionParentKind kind, UUID parentId, UUID classId) {
        switch (kind) {
            case HOMEWORK -> {
                Homework h = homework.findById(parentId)
                        .orElseThrow(() -> AppException.notFound("Homework"));
                if (!h.getClassId().equals(classId)) {
                    throw AppException.notFound("Homework");
                }
            }
            case LESSON -> {
                Lesson l = lessons.findById(parentId)
                        .orElseThrow(() -> AppException.notFound("Lesson"));
                if (!l.getClassId().equals(classId)) {
                    throw AppException.notFound("Lesson");
                }
            }
        }
    }

    private static String requireBody(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Body is required");
        }
        if (t.length() > MAX_BODY_LEN) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "Body is too long");
        }
        return t;
    }

    /**
     * Student-side ownership check: load a question by id but throw
     * 404 if it doesn't exist or if it belongs to someone else. The
     * 404 is deliberate — a student fishing for other students'
     * question ids should get the same response as a totally missing
     * id, so there's no way to enumerate threads via the response
     * shape.
     */
    private BlockQuestion loadOwnedByStudent(AuthenticatedUser principal, UUID questionId) {
        BlockQuestion q = questions.findById(questionId)
                .orElseThrow(() -> AppException.notFound("Question"));
        if (!q.getStudentUserId().equals(principal.userId())) {
            throw AppException.notFound("Question");
        }
        return q;
    }

    /**
     * Student-side parent visibility: load the homework / lesson the
     * thread is pinned to and confirm the student can see it. Returns
     * the parent's classId so callers can stamp it on the new
     * question without a second fetch.
     *
     * <p>404 hides existence on:
     * <ul>
     *   <li>parent not found,</li>
     *   <li>parent archived (mirrors the StudentHomeworkDetail /
     *       lesson detail "you can't see archived material" rule),</li>
     *   <li>caller not on the parent's class roster.</li>
     * </ul>
     */
    private UUID loadStudentVisibleParent(AuthenticatedUser principal,
                                          BlockQuestionParentKind kind,
                                          UUID parentId) {
        UUID classId;
        switch (kind) {
            case HOMEWORK -> {
                Homework h = homework.findById(parentId)
                        .orElseThrow(() -> AppException.notFound("Homework"));
                if (h.getArchivedAt() != null) {
                    throw AppException.notFound("Homework");
                }
                classId = h.getClassId();
            }
            case LESSON -> {
                Lesson l = lessons.findById(parentId)
                        .orElseThrow(() -> AppException.notFound("Lesson"));
                if (l.getArchivedAt() != null) {
                    throw AppException.notFound("Lesson");
                }
                classId = l.getClassId();
            }
            default -> throw new AppException(ErrorCode.BAD_REQUEST, "Unknown parent kind");
        }
        if (!studentRosters.existsByIdClassIdAndIdStudentUserId(classId, principal.userId())) {
            // 404 not 403 — same shape as other student paths so we
            // don't leak the parent's existence to non-enrolled callers.
            throw AppException.notFound(kind == BlockQuestionParentKind.HOMEWORK
                    ? "Homework" : "Lesson");
        }
        return classId;
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
     * Class authorisation — same shape as {@link HomeworkService} (which
     * see for the rationale on duplicating across services rather than
     * extracting a base class).
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
                    throw AppException.forbidden("Teachers may only manage Q&A for their own classes");
                }
            } else {
                throw AppException.forbidden("Not allowed to manage Q&A");
            }
        }
        return clazz;
    }
}
