package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.CreateLessonRequest;
import com.chessedu.platform.classes.dto.LessonDetail;
import com.chessedu.platform.classes.dto.LessonSummary;
import com.chessedu.platform.classes.dto.UpdateLessonRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin / teacher API for lessons published to a class. Mirrors
 * {@link AdminHomeworkController} — same URL shape, same authorisation
 * contract — but without any teacher-override / attempt-review
 * endpoints (lesson completion is student self-report only in v1).
 *
 * <p>Mounted under the existing admin-classes path:
 * {@code /api/v1/admin/classes/{classId}/lessons}.
 *
 * <p>All endpoints are open to SCHOOL_ADMIN, PLATFORM_ADMIN, and
 * TEACHER; the service layer then enforces that teachers may only
 * touch lessons for classes they are assigned to.
 */
@RestController
@RequestMapping("/api/v1/admin/classes/{classId}/lessons")
@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
public class AdminLessonController {

    private final LessonService service;

    public AdminLessonController(LessonService service) {
        this.service = service;
    }

    @GetMapping
    public List<LessonSummary> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @RequestParam(value = "includeArchived", required = false, defaultValue = "false")
                    boolean includeArchived
    ) {
        return service.list(principal, classId, includeArchived);
    }

    @PostMapping
    public LessonDetail create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @Valid @RequestBody CreateLessonRequest body
    ) {
        return service.create(principal, classId, body);
    }

    @GetMapping("/{id}")
    public LessonDetail detail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id
    ) {
        return service.detail(principal, classId, id);
    }

    @PatchMapping("/{id}")
    public LessonDetail update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateLessonRequest body
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
}
