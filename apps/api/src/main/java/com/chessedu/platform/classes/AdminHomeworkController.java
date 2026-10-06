package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.BlockAttemptView;
import com.chessedu.platform.classes.dto.CreateHomeworkRequest;
import com.chessedu.platform.classes.dto.HomeworkDetail;
import com.chessedu.platform.classes.dto.HomeworkSummary;
import com.chessedu.platform.classes.dto.ReviewBlockAttemptRequest;
import com.chessedu.platform.classes.dto.UpdateHomeworkRequest;
import com.chessedu.platform.classes.dto.UpdateHomeworkSubmissionRequest;
import com.chessedu.platform.classes.model.HomeworkBlockAttempt;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin / teacher API for homework assigned to a class.
 *
 * <p>Mounted under the existing admin-classes path:
 * {@code /api/v1/admin/classes/{classId}/homework}.
 *
 * <p>All endpoints are open to SCHOOL_ADMIN, PLATFORM_ADMIN, and TEACHER;
 * the service layer then enforces that teachers may only touch homework for
 * classes they are assigned to.
 */
@RestController
@RequestMapping("/api/v1/admin/classes/{classId}/homework")
@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
public class AdminHomeworkController {

    private final HomeworkService service;
    private final HomeworkBlockAttemptService attemptService;

    public AdminHomeworkController(HomeworkService service,
                                   HomeworkBlockAttemptService attemptService) {
        this.service = service;
        this.attemptService = attemptService;
    }

    @GetMapping
    public List<HomeworkSummary> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @RequestParam(value = "includeArchived", required = false, defaultValue = "false")
                    boolean includeArchived
    ) {
        return service.list(principal, classId, includeArchived);
    }

    @PostMapping
    public HomeworkDetail create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @Valid @RequestBody CreateHomeworkRequest body
    ) {
        return service.create(principal, classId, body);
    }

    @GetMapping("/{id}")
    public HomeworkDetail detail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id
    ) {
        return service.detail(principal, classId, id);
    }

    @PatchMapping("/{id}")
    public HomeworkDetail update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateHomeworkRequest body
    ) {
        return service.update(principal, classId, id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id
    ) {
        service.archive(principal, classId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Teacher / admin override of a single student's submission state. PUT
     * (upsert) rather than POST — the grain (homework, student) is unique.
     */
    @PutMapping("/{id}/submissions/{studentUserId}")
    public HomeworkDetail updateSubmission(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id,
            @PathVariable("studentUserId") UUID studentUserId,
            @Valid @RequestBody UpdateHomeworkSubmissionRequest body
    ) {
        return service.updateSubmission(principal, classId, id, studentUserId, body);
    }

    /**
     * Review a single block-level attempt. The attempt must be the live row
     * in its chain — reviewing a superseded attempt returns 409 so teacher
     * actions always apply to what the student currently sees. The request
     * body carries the outcome (OK / NEEDS_WORK) and an optional teacher
     * note.
     */
    @PostMapping("/{id}/attempts/{attemptId}/review")
    public BlockAttemptView reviewAttempt(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id,
            @PathVariable("attemptId") UUID attemptId,
            @Valid @RequestBody ReviewBlockAttemptRequest body
    ) {
        // classId / homeworkId are in the path mostly for REST shape +
        // routing; the service authorises via the attempt's stored
        // homeworkId → classId chain.
        HomeworkBlockAttempt a = attemptService.reviewAttempt(
                principal, attemptId,
                body == null ? null : body.status(),
                body == null ? null : body.teacherNote());
        return HomeworkService.toBlockAttemptView(a);
    }

    /**
     * Full history for one (homework, student, block) triple, oldest first.
     * Used by the teacher's audit view when they want to scroll back through
     * prior attempts on a block.
     */
    @GetMapping("/{id}/students/{studentUserId}/blocks/{blockIndex}/attempts")
    public List<BlockAttemptView> attemptHistory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id,
            @PathVariable("studentUserId") UUID studentUserId,
            @PathVariable("blockIndex") int blockIndex
    ) {
        List<HomeworkBlockAttempt> rows = attemptService.historyForAdmin(
                principal, classId, id, studentUserId, blockIndex);
        List<BlockAttemptView> out = new java.util.ArrayList<>(rows.size());
        for (HomeworkBlockAttempt a : rows) {
            out.add(HomeworkService.toBlockAttemptView(a));
        }
        return out;
    }
}
