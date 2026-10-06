package com.chessedu.platform.classes;

import com.chessedu.platform.classes.dto.ClassGroupDetail;
import com.chessedu.platform.classes.dto.ClassGroupSummary;
import com.chessedu.platform.classes.dto.CreateClassRequest;
import com.chessedu.platform.classes.dto.RosterMemberRequest;
import com.chessedu.platform.classes.dto.UpdateClassRequest;
import com.chessedu.platform.classes.model.ClassGroupStatus;
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
 * Admin API for classes and their rosters.
 *
 * <p>Authorisation mirrors the other admin endpoints: SCHOOL_ADMIN for their
 * own school only; PLATFORM_ADMIN may target any school by passing
 * {@code schoolId}. PLATFORM_ADMIN without a {@code schoolId} on list returns
 * an empty page rather than silently aggregating across tenants.
 */
@RestController
@RequestMapping("/api/v1/admin/classes")
@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
public class AdminClassController {

    private final ClassService service;

    public AdminClassController(ClassService service) {
        this.service = service;
    }

    // --- list / detail -------------------------------------------------

    @GetMapping
    public List<ClassGroupSummary> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(value = "schoolId", required = false) UUID schoolId,
            @RequestParam(value = "status", required = false) ClassGroupStatus status,
            @RequestParam(value = "search", required = false) String search
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId);
        if (target == null) return List.of();
        return service.listForSchool(target, status, search);
    }

    @GetMapping("/{id}")
    public ClassGroupDetail detail(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, null);
        if (target == null) {
            // PLATFORM_ADMIN didn't provide schoolId — we still need one to
            // scope the lookup. Ask them to include it.
            throw new AppException(ErrorCode.BAD_REQUEST, "schoolId is required");
        }
        return service.detail(target, id);
    }

    // --- create / update ----------------------------------------------

    @PostMapping
    public ClassGroupDetail create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateClassRequest body
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, null);
        if (target == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "schoolId is required");
        }
        return service.create(target, body);
    }

    @PatchMapping("/{id}")
    public ClassGroupDetail update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateClassRequest body
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, null);
        if (target == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "schoolId is required");
        }
        return service.update(target, id, body);
    }

    // --- roster --------------------------------------------------------

    @PostMapping("/{id}/teachers")
    public ResponseEntity<Void> addTeacher(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody RosterMemberRequest body
    ) {
        requireAuth(principal);
        UUID target = requireSchool(principal);
        service.addTeacher(target, id, body.userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/teachers/{userId}")
    public ResponseEntity<Void> removeTeacher(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @PathVariable("userId") UUID userId
    ) {
        requireAuth(principal);
        UUID target = requireSchool(principal);
        service.removeTeacher(target, id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/students")
    public ResponseEntity<Void> addStudent(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody RosterMemberRequest body
    ) {
        requireAuth(principal);
        UUID target = requireSchool(principal);
        service.addStudent(target, id, body.userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/students/{userId}")
    public ResponseEntity<Void> removeStudent(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID id,
            @PathVariable("userId") UUID userId
    ) {
        requireAuth(principal);
        UUID target = requireSchool(principal);
        service.removeStudent(target, id, userId);
        return ResponseEntity.noContent().build();
    }

    // --- helpers -------------------------------------------------------

    private static UUID resolveSchool(AuthenticatedUser principal, UUID requested) {
        if (principal.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            return requested; // may be null
        }
        if (principal.role() != Role.SCHOOL_ADMIN) {
            throw AppException.forbidden("Only school admins may manage classes");
        }
        if (requested != null && !requested.equals(principal.schoolId())) {
            throw AppException.forbidden("Cross-school access not permitted");
        }
        return principal.schoolId();
    }

    private static UUID requireSchool(AuthenticatedUser principal) {
        UUID s = resolveSchool(principal, null);
        if (s == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "schoolId is required");
        }
        return s;
    }

    private static void requireAuth(AuthenticatedUser principal) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
    }
}
