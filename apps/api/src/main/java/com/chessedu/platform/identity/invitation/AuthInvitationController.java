package com.chessedu.platform.identity.invitation;

import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.invitation.dto.AcceptInvitationRequest;
import com.chessedu.platform.identity.invitation.dto.InvitationPreview;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints the recipient's browser hits without a session:
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/invitations/{token}} — preview so the accept
 *       page can render the header ("You're invited as TEACHER at Demo School").</li>
 *   <li>{@code POST /api/v1/auth/invitations/accept} — set a password, create
 *       the user, auto-log in.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth/invitations")
public class AuthInvitationController {

    private final InvitationService invitations;

    public AuthInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping("/{token}")
    public InvitationPreview preview(@PathVariable("token") String token) {
        return invitations.preview(token);
    }

    @PostMapping("/accept")
    public TokenResponse accept(@Valid @RequestBody AcceptInvitationRequest body,
                                HttpServletRequest http) {
        return invitations.accept(body, http);
    }
}
