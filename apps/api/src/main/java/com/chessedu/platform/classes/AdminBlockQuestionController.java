package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.BlockQuestionInboxRow;
import com.chessedu.platform.classes.dto.BlockQuestionThreadView;
import com.chessedu.platform.classes.dto.CreateBlockQuestionReplyRequest;
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
 * Admin / teacher API for block-level Q&amp;A.
 *
 * <p>Routes mounted under {@code /api/v1/admin/classes/{classId}}:
 * <ul>
 *   <li><b>GET /questions</b> — class inbox (unresolved threads on
 *       non-archived parents, oldest first).</li>
 *   <li><b>GET /questions/{questionId}</b> — full thread (head +
 *       replies).</li>
 *   <li><b>POST /questions/{questionId}/replies</b> — teacher / admin
 *       reply. Returns the assembled thread.</li>
 *   <li><b>POST /questions/{questionId}/resolve</b> — close thread.
 *       Returns the assembled thread.</li>
 *   <li><b>GET /homework/{homeworkId}/blocks/{blockIndex}/questions</b>
 *       — per-block overlay for a homework. Mounted under the homework
 *       URL shape so the admin block-review surface can fetch threads
 *       inline with the block it's reviewing.</li>
 *   <li><b>GET /lessons/{lessonId}/blocks/{blockIndex}/questions</b>
 *       — same shape for lessons.</li>
 * </ul>
 *
 * <p>All routes require SCHOOL_ADMIN, PLATFORM_ADMIN, or TEACHER. The
 * service layer enforces that teachers may only manage threads in
 * classes they are assigned to.
 */
@RestController
@RequestMapping("/api/v1/admin/classes/{classId}")
@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
public class AdminBlockQuestionController {

    private final BlockQuestionService service;

    public AdminBlockQuestionController(BlockQuestionService service) {
        this.service = service;
    }

    @GetMapping("/questions")
    public List<BlockQuestionInboxRow> inbox(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId
    ) {
        return service.inbox(principal, classId);
    }

    @GetMapping("/questions/{questionId}")
    public BlockQuestionThreadView thread(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("questionId") UUID questionId
    ) {
        return service.getThread(principal, classId, questionId);
    }

    @PostMapping("/questions/{questionId}/replies")
    public BlockQuestionThreadView reply(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("questionId") UUID questionId,
            @Valid @RequestBody CreateBlockQuestionReplyRequest body
    ) {
        return service.replyAsTeacher(principal, classId, questionId,
                body == null ? null : body.body());
    }

    @PostMapping("/questions/{questionId}/resolve")
    public BlockQuestionThreadView resolve(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("questionId") UUID questionId
    ) {
        return service.resolveThread(principal, classId, questionId);
    }

    @GetMapping("/homework/{homeworkId}/blocks/{blockIndex}/questions")
    public List<BlockQuestionThreadView> homeworkBlockOverlay(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("homeworkId") UUID homeworkId,
            @PathVariable("blockIndex") int blockIndex
    ) {
        return service.listForBlock(principal, classId,
                BlockQuestionParentKind.HOMEWORK, homeworkId, blockIndex);
    }

    @GetMapping("/lessons/{lessonId}/blocks/{blockIndex}/questions")
    public List<BlockQuestionThreadView> lessonBlockOverlay(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("lessonId") UUID lessonId,
            @PathVariable("blockIndex") int blockIndex
    ) {
        return service.listForBlock(principal, classId,
                BlockQuestionParentKind.LESSON, lessonId, blockIndex);
    }
}
