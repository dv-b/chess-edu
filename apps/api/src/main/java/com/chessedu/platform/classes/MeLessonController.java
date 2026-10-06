package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.StudentLessonDetail;
import com.chessedu.platform.classes.dto.StudentLessonView;
import com.chessedu.platform.classes.dto.UpdateMyCompletionRequest;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Student self-service lesson API. Sibling to {@link MeHomeworkController};
 * the shape and {@code /me/} convention match exactly — only the scope
 * differs (lessons are revisitable study material, no due dates, no
 * teacher-side reviews, no block-level attempt history).
 *
 * <p>Mounted under {@code /api/v1/me/lessons}. Endpoints here never
 * require an explicit user id; the authenticated principal is always the
 * target. Everything is gated on {@code hasRole('STUDENT')} — parents
 * have no lesson-of-child endpoint in v1 (homework has one; add here
 * only when a parent surface actually needs it).
 *
 * <p>There's no student-facing "submit" endpoint and no "reveal" — the
 * client owns interactive state for the four v1 block kinds (MCQ guess,
 * board-walkthrough progress, video playhead). Only the aggregate
 * completion signal (READ / NOT_STARTED / null) is server-persisted.
 */
@RestController
@RequestMapping("/api/v1/me/lessons")
@PreAuthorize("hasRole('STUDENT')")
public class MeLessonController {

    private final LessonService service;

    public MeLessonController(LessonService service) {
        this.service = service;
    }

    /** Active lessons across every class the caller is enrolled in. */
    @GetMapping
    public List<StudentLessonView> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.listForStudent(principal);
    }

    /**
     * Full detail for a single lesson: the lesson, the caller's own
     * completion status, and the ordered content blocks. 404 for
     * lessons the caller can't see (missing / archived / out-of-class).
     */
    @GetMapping("/{id}")
    public StudentLessonDetail detail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id
    ) {
        return service.detailForStudent(principal, id);
    }

    /**
     * Student's own completion mark on a single lesson. PUT (upsert)
     * rather than POST — the (lesson, student) grain is unique by
     * construction. A null status clears the row back to the implicit
     * "not started" state.
     */
    @PutMapping("/{id}/completion")
    public StudentLessonView updateMine(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateMyCompletionRequest body
    ) {
        return service.updateMyCompletion(principal, id, body);
    }
}
