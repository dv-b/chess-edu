package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.AttendanceSheetView;
import com.chessedu.platform.classes.dto.UpdateAttendanceRequest;
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

import java.util.UUID;

/**
 * Attendance sheet for a single class session.
 *
 * <p>Mounted under the existing admin-classes path so URLs continue to read
 * naturally: {@code GET /api/v1/admin/classes/{classId}/sessions/{sessionId}/attendance}.
 *
 * <p>Both {@code GET} and {@code PUT} are restricted to SCHOOL_ADMIN,
 * PLATFORM_ADMIN, and TEACHER. The {@link AttendanceService} layer then
 * enforces the finer rule that teachers may only touch classes they're
 * assigned to (the {@code hasAnyRole} check alone would let any teacher
 * mark any class in the system).
 */
@RestController
@RequestMapping("/api/v1/admin/classes/{classId}/sessions/{sessionId}/attendance")
public class AdminAttendanceController {

    private final AttendanceService service;

    public AdminAttendanceController(AttendanceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
    public AttendanceSheetView read(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("sessionId") UUID sessionId
    ) {
        return service.read(principal, classId, sessionId);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
    public AttendanceSheetView update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("sessionId") UUID sessionId,
            @Valid @RequestBody UpdateAttendanceRequest body
    ) {
        return service.update(principal, classId, sessionId, body);
    }
}
