package com.chessedu.platform.identity.invitation;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.invitation.dto.InvitationView;
import com.chessedu.platform.identity.invitation.dto.IssueInvitationRequest;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin API for invitations.
 *
 * <p>Authorisation: any request landing here must have SCHOOL_ADMIN or
 * PLATFORM_ADMIN authority (enforced by {@link org.springframework.security.access.prepost.PreAuthorize}
 * and reinforced by {@link InvitationService} based on the invitation being
 * read/written).
 */
@RestController
@RequestMapping("/api/v1/admin/invitations")
@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN')")
public class AdminInvitationController {

    private final InvitationService invitations;

    public AdminInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    public InvitationView issue(@AuthenticationPrincipal AuthenticatedUser principal,
                                @Valid @RequestBody IssueInvitationRequest body) {
        requireAuth(principal);
        return invitations.issue(principal, body);
    }

    @GetMapping
    public List<InvitationView> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @RequestParam(value = "schoolId", required = false) UUID schoolId) {
        requireAuth(principal);
        UUID target = resolveSchool(principal, schoolId);
        return invitations.listForSchool(target);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable("id") UUID id) {
        requireAuth(principal);
        invitations.revoke(principal, id);
        return ResponseEntity.noContent().build();
    }

    private static UUID resolveSchool(AuthenticatedUser principal, UUID requested) {
        if (principal.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            // Platform admin: may filter across any school; when omitted, list nothing
            // rather than pretend to aggregate.
            return requested;
        }
        if (principal.role() != Role.SCHOOL_ADMIN) {
            throw AppException.forbidden("Only school admins may list invitations");
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
