package com.chessedu.platform.identity.invitation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing view of an invitation. Does NOT include the raw token —
 * we only keep the hash once the invite is issued.
 */
public record InvitationView(
        UUID id,
        UUID schoolId,
        String schoolName,
        String role,
        String email,
        String displayName,
        UUID invitedBy,
        Instant issuedAt,
        Instant expiresAt,
        Instant acceptedAt,
        boolean expired
) {
}
