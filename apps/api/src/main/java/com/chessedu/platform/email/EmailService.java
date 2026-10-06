package com.chessedu.platform.email;

import java.util.Locale;

/**
 * Thin abstraction over outbound email so domain services don't import
 * Spring Mail. One method per template keeps the contract obvious at the
 * call site (no loose "send" with magic keys).
 */
public interface EmailService {

    /**
     * Sends an invitation email with the accept link. The subject and body are
     * rendered in {@code locale}; unknown locales fall back to English.
     */
    void sendInvitation(InvitationEmail email, Locale locale);

    /**
     * Details needed to render an invitation email body.
     */
    record InvitationEmail(
            String to,
            String recipientName,
            String inviterName,
            String schoolName,        // nullable for PLATFORM_ADMIN invites
            String role,              // display label in the recipient's locale
            String acceptUrl,
            String expiresAtLocal     // pre-formatted absolute timestamp
    ) {
    }
}
