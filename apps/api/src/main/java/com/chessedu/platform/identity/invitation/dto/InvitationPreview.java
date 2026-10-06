package com.chessedu.platform.identity.invitation.dto;

import java.time.Instant;

/**
 * Public-facing preview shown on the accept page BEFORE the recipient
 * sets their password. We expose the minimum needed to render the form
 * header ("You've been invited as TEACHER at Demo Chess School").
 *
 * <p>Note we don't surface the inviter's name on this endpoint; it's
 * optional, low-value info and adds a DB lookup.
 */
public record InvitationPreview(
        String email,
        String displayName,
        String role,
        String schoolName,
        String preferredLocale,
        Instant expiresAt
) {
}
