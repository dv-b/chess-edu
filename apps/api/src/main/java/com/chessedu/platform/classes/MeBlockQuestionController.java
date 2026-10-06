package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.BlockQuestionThreadView;
import com.chessedu.platform.classes.dto.CreateBlockQuestionReplyRequest;
import com.chessedu.platform.classes.dto.OpenBlockQuestionRequest;
import com.chessedu.platform.classes.model.BlockQuestionParentKind;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Student self-service Q&amp;A API.
 *
 * <p>Mounted under {@code /api/v1/me} — the same {@code /me/} convention
 * the rest of the student surface uses. Authorisation:
 * <ul>
 *   <li>Caller must have the STUDENT role (gated by {@code @PreAuthorize}
 *       at the class level).</li>
 *   <li>Caller must be enrolled in the parent's class for any per-block
 *       op (open / list).</li>
 *   <li>Caller must own the question for any per-thread op (read /
 *       reply / self-resolve).</li>
 * </ul>
 *
 * <p>404 hides existence on every "you can't see this" path so a
 * crafted URL doesn't leak whether other students' threads exist.
 *
 * <h2>Why two parent-prefixed routes for open + list</h2>
 * Mirrors the admin-side per-block overlay: the frontend already
 * knows the parent kind from the page it's on
 * ({@code /student/homework/{id}} vs {@code /student/lessons/{id}}),
 * so dedicated paths keep the URL shapes natural and let us avoid
 * a {@code parentKind} body / query parameter that would be a sneaky
 * trust boundary.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("hasRole('STUDENT')")
public class MeBlockQuestionController {

    private final BlockQuestionService service;

    public MeBlockQuestionController(BlockQuestionService service) {
        this.service = service;
    }

    // -------- Per-block list (mine only) ---------------------------------

    @GetMapping("/homework/{homeworkId}/blocks/{blockIndex}/questions")
    public List<BlockQuestionThreadView> myHomeworkBlockThreads(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("homeworkId") UUID homeworkId,
            @PathVariable("blockIndex") int blockIndex
    ) {
        return service.listMyThreadsForBlock(principal,
                BlockQuestionParentKind.HOMEWORK, homeworkId, blockIndex);
    }

    @GetMapping("/lessons/{lessonId}/blocks/{blockIndex}/questions")
    public List<BlockQuestionThreadView> myLessonBlockThreads(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("lessonId") UUID lessonId,
            @PathVariable("blockIndex") int blockIndex
    ) {
        return service.listMyThreadsForBlock(principal,
                BlockQuestionParentKind.LESSON, lessonId, blockIndex);
    }

    // -------- Open new thread --------------------------------------------

    @PostMapping("/homework/{homeworkId}/blocks/{blockIndex}/questions")
    public BlockQuestionThreadView openHomeworkThread(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("homeworkId") UUID homeworkId,
            @PathVariable("blockIndex") int blockIndex,
            @Valid @RequestBody OpenBlockQuestionRequest body
    ) {
        return service.openThread(principal,
                BlockQuestionParentKind.HOMEWORK, homeworkId, blockIndex,
                body == null ? null : body.body());
    }

    @PostMapping("/lessons/{lessonId}/blocks/{blockIndex}/questions")
    public BlockQuestionThreadView openLessonThread(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("lessonId") UUID lessonId,
            @PathVariable("blockIndex") int blockIndex,
            @Valid @RequestBody OpenBlockQuestionRequest body
    ) {
        return service.openThread(principal,
                BlockQuestionParentKind.LESSON, lessonId, blockIndex,
                body == null ? null : body.body());
    }

    // -------- Per-thread ops (kind-agnostic, by question id) -------------

    /**
     * Cross-class rollup — every thread the caller has opened,
     * newest first. Powers the {@code /student/questions} list view
     * (Lane B / B4) so a student can scan all their conversations in
     * one place without revisiting each homework / lesson page.
     */
    @GetMapping("/questions")
    public List<BlockQuestionThreadView> listAllMine(
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return service.listAllMyThreads(principal);
    }

    @GetMapping("/questions/{questionId}")
    public BlockQuestionThreadView myThread(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("questionId") UUID questionId
    ) {
        return service.getMyThread(principal, questionId);
    }

    @PostMapping("/questions/{questionId}/replies")
    public BlockQuestionThreadView reply(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("questionId") UUID questionId,
            @Valid @RequestBody CreateBlockQuestionReplyRequest body
    ) {
        return service.replyAsStudent(principal, questionId,
                body == null ? null : body.body());
    }

    @PostMapping("/questions/{questionId}/resolve")
    public BlockQuestionThreadView resolve(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("questionId") UUID questionId
    ) {
        return service.selfResolve(principal, questionId);
    }
}
