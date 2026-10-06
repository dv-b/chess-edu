package com.chessedu.platform.identity.invitation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/auth/invitations/accept}.
 *
 * <p>The recipient clicks the email link (which carries the raw token),
 * chooses a password, and optionally adjusts their displayed name / locale
 * before submitting the form.
 */
public record AcceptInvitationRequest(
        @NotBlank @Size(max = 512)
        String token,

        @NotBlank @Size(min = 8, max = 200)
        String password,

        @Size(max = 160)
        String displayName,

        @Pattern(regexp = "uz|ru|en")
        String preferredLocale
) {
}
