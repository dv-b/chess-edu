package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.BlockAttemptView;
import com.chessedu.platform.classes.dto.StudentHomeworkDetail;
import com.chessedu.platform.classes.dto.StudentHomeworkView;
import com.chessedu.platform.classes.dto.SubmitBlockAttemptRequest;
import com.chessedu.platform.classes.dto.UpdateMySubmissionRequest;
import com.chessedu.platform.classes.model.HomeworkBlockAttempt;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Student self-service homework API.
 *
 * <p>Mounted under {@code /api/v1/me/homework} — the same {@code /me/}
 * convention the web app already uses for "things scoped to the caller".
 * Endpoints here never require an explicit user id; the authenticated
 * principal is always the target.
 */
@RestController
@RequestMapping("/api/v1/me/homework")
@PreAuthorize("hasRole('STUDENT')")
public class MeHomeworkController {

    private final HomeworkService service;
    private final HomeworkBlockAttemptService attemptService;

    public MeHomeworkController(HomeworkService service,
                                HomeworkBlockAttemptService attemptService) {
        this.service = service;
        this.attemptService = attemptService;
    }

    /** Active homework across every class the caller is enrolled in. */
    @GetMapping
    public List<StudentHomeworkView> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.listForStudent(principal);
    }

    /**
     * Full detail for a single homework: the assignment, the caller's own
     * submission status, and the ordered content blocks (with teacher-only
     * fields stripped server-side). 404 for homework the caller can't see.
     */
    @GetMapping("/{id}")
    public StudentHomeworkDetail detail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id
    ) {
        return service.detailForStudent(principal, id);
    }

    /**
     * Student's own mark on a single assignment. PUT (upsert) rather than
     * POST — the (homework, student) grain is unique by construction.
     * A null status clears the submission back to the implicit
     * "not started" state.
     */
    @PutMapping("/{id}/status")
    public StudentHomeworkView updateMine(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateMySubmissionRequest body
    ) {
        return service.updateMySubmission(principal, id, body);
    }

    /**
     * Submit moves for one block of one homework. History-preserving: any
     * previous live attempt on the same (homework, student, block) is
     * stamped as replaced and a fresh row is inserted. See
     * {@link HomeworkBlockAttemptService#submitAttempt} for the full
     * transaction contract.
     */
    @PostMapping("/{id}/blocks/{blockIndex}/attempts")
    public BlockAttemptView submitAttempt(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @PathVariable("blockIndex") int blockIndex,
            @RequestBody SubmitBlockAttemptRequest body
    ) {
        HomeworkBlockAttempt a = attemptService.submitAttempt(
                principal, id, blockIndex, body == null ? null : body.moves());
        return HomeworkService.toBlockAttemptView(a);
    }

    /**
     * Unlock the recorded solution on the caller's live attempt for one
     * block. Only valid after a teacher has reviewed the attempt with
     * "needs work". Idempotent. After reveal, the next GET of the homework
     * detail will include the solution inside that block's payload.
     */
    @PostMapping("/{id}/blocks/{blockIndex}/reveal-solution")
    public BlockAttemptView revealSolution(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @PathVariable("blockIndex") int blockIndex
    ) {
        HomeworkBlockAttempt a = attemptService.revealSolution(principal, id, blockIndex);
        return HomeworkService.toBlockAttemptView(a);
    }
}
