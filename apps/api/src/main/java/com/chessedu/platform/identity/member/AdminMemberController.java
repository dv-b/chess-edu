package com.chessedu.platform.identity.member;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.member.dto.MemberView;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin API for listing members of a school.
 *
 * <p>Authorisation mirrors {@link com.chessedu.platform.identity.invitation.AdminInvitationController}:
 * SCHOOL_ADMIN for their own school only; PLATFORM_ADMIN may pass an explicit
 * {@code schoolId}. If a PLATFORM_ADMIN omits {@code schoolId} we return an
 * empty list rather than silently aggregating across tenants.
 */
@RestController
@RequestMapping("/api/v1/admin/members")
@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
public class AdminMemberController {

    private final MemberService members;

    public AdminMemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public List<MemberView> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(value = "schoolId", required = false) UUID schoolId,
            @RequestParam(value = "role", required = false) Role role,
            @RequestParam(value = "search", required = false) String search
    ) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId);
        if (target == null) {
            return List.of();
        }
        return members.listForSchool(target, role, search);
    }

    private static UUID resolveSchool(AuthenticatedUser principal, UUID requested) {
        if (principal.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            return requested; // may be null → empty list
        }
        if (principal.role() != Role.SCHOOL_ADMIN) {
            throw AppException.forbidden("Only school admins may list members");
        }
        if (requested != null && !requested.equals(principal.schoolId())) {
            throw AppException.forbidden("Cross-school listing not permitted");
        }
        return principal.schoolId();
    }

    private static void requireAuth(AuthenticatedUser principal) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
    }
}
