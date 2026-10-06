package com.chessedu.platform.identity.invitation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/admin/invitations}.
 *
 * <p>{@code role} is validated with a regex rather than a binding to {@code Role}
 * because the enum set includes {@code PLATFORM_ADMIN} which isn't a
 * school-scoped {@link com.chessedu.platform.identity.model.Role}. The service
 * validates the final set of allowed values based on the caller's authorities.
 *
 * <p>{@code schoolId} is optional: SCHOOL_ADMIN callers have their school
 * inferred from the principal; PLATFORM_ADMIN callers must supply it (or omit
 * it entirely for PLATFORM_ADMIN invitations).
 */
public record IssueInvitationRequest(
        @NotBlank @Pattern(regexp = "SCHOOL_ADMIN|TEACHER|PARENT|STUDENT|PLATFORM_ADMIN")
        String role,

        @NotBlank @Email @Size(max = 240)
        String email,

        @NotBlank @Size(min = 1, max = 160)
        String displayName,

        UUID schoolId,

        @Pattern(regexp = "uz|ru|en")
        String preferredLocale
) {
}
