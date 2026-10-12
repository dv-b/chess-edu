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
        boolean expired,
        /** {@code EMAIL} | {@code TELEGRAM}. */
        String deliveryChannel,
        /** Bot-funnel state for TELEGRAM invites; null for EMAIL. */
        String botState,
        /**
         * {@code https://t.me/<bot>?start=INV_<token>} — present ONLY in the
         * issue response of a TELEGRAM invite (the raw token is never stored),
         * never in list views. The admin forwards it to the invitee.
         */
        String botDeeplink,
        /** E.164 phone recorded for phone-matched Telegram invites; null otherwise. */
        String phone,
        /** The bot's @username (no '@') for TELEGRAM invites, so the UI can say "open @bot". */
        String botUsername
) {
}
