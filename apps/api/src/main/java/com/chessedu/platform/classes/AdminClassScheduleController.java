package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.ClassSessionView;
import com.chessedu.platform.classes.dto.CreatePatternRequest;
import com.chessedu.platform.classes.dto.CreateSessionRequest;
import com.chessedu.platform.classes.dto.SchedulePatternView;
import com.chessedu.platform.classes.dto.UpdateSessionRequest;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
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
 * Schedule API for a class — patterns and concrete sessions.
 *
 * <p>Mounted under {@code /api/v1/admin/classes/{classId}} so URLs read
 * naturally: {@code POST /api/v1/admin/classes/{classId}/patterns}.
 *
 * <p>Authorisation: SCHOOL_ADMIN may write within their school; PLATFORM_ADMIN
 * may write anywhere by passing {@code schoolId}; TEACHER may read but not
 * write — they need to see the calendar for classes they lead, but only
 * admins shape the schedule. The class-scoped read endpoints intentionally
 * do not require admin role; the broader {@link com.chessedu.platform.security.SecurityConfig}
 * filter chain handles the auth check.
 */
@RestController
@RequestMapping("/api/v1/admin/classes/{classId}")
public class AdminClassScheduleController {

    private final ClassScheduleService service;

    public AdminClassScheduleController(ClassScheduleService service) {
        this.service = service;
    }

    // ---- patterns -----------------------------------------------------

    @GetMapping("/patterns")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
    public List<SchedulePatternView> listPatterns(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ false);
        return service.listPatterns(target, classId);
    }

    @PostMapping("/patterns")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
    public SchedulePatternView createPattern(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId,
            @Valid @RequestBody CreatePatternRequest body
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ true);
        return service.createPattern(target, classId, body);
    }

    @DeleteMapping("/patterns/{patternId}")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> deletePattern(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("patternId") UUID patternId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ true);
        service.deletePattern(target, classId, patternId);
        return ResponseEntity.noContent().build();
    }

    // ---- sessions -----------------------------------------------------

    @GetMapping("/sessions")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN', 'TEACHER')")
    public List<ClassSessionView> listSessions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId,
            @RequestParam(value = "upcomingOnly", defaultValue = "false") boolean upcomingOnly
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ false);
        return service.listSessions(target, classId, upcomingOnly);
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
    public ClassSessionView createSession(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId,
            @Valid @RequestBody CreateSessionRequest body
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ true);
        return service.createSession(target, classId, body);
    }

    @PatchMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
    public ClassSessionView updateSession(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("sessionId") UUID sessionId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId,
            @Valid @RequestBody UpdateSessionRequest body
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ true);
        return service.updateSession(target, classId, sessionId, body);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Void> deleteSession(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("classId") UUID classId,
            @PathVariable("sessionId") UUID sessionId,
            @RequestParam(value = "schoolId", required = false) UUID schoolId
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId, /* writing */ true);
        service.deleteSession(target, classId, sessionId);
        return ResponseEntity.noContent().build();
    }

    // ---- helpers ------------------------------------------------------

    /**
     * Resolve which school the principal is acting within. PLATFORM_ADMIN may
     * pass an explicit {@code schoolId}; everyone else is locked to their own.
     * For writes we require a concrete school; for reads we error if a
     * platform-admin omits it (otherwise we'd need a cross-tenant query).
     */
    private static UUID resolveSchool(AuthenticatedUser principal, UUID requested, boolean writing) {
        if (principal.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            if (requested == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "schoolId is required");
            }
            return requested;
        }
        if (principal.role() != Role.SCHOOL_ADMIN
                && principal.role() != Role.TEACHER) {
            throw AppException.forbidden("Not allowed to view schedules");
        }
        if (writing && principal.role() != Role.SCHOOL_ADMIN) {
            throw AppException.forbidden("Only school admins may edit the schedule");
        }
        if (requested != null && !requested.equals(principal.schoolId())) {
            throw AppException.forbidden("Cross-school access not permitted");
        }
        return principal.schoolId();
    }

    private static void requireAuth(AuthenticatedUser principal) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
    }
}
