package com.chessedu.platform.identity.model;

/**
 * Funnel state of a TELEGRAM-delivered {@link Invitation}, tracking where the
 * invitee is in the bot conversation. {@code null} on EMAIL invitations — the
 * funnel concept only applies to the bot flow.
 *
 * <pre>
 *   PENDING           invite issued, bot not yet started
 *   STARTED           invitee ran /start INV_&lt;token&gt;, token validated
 *   AWAITING_CONTACT  bot asked for the "Share Contact" tap, waiting
 *   COMPLETED         contact shared → user + telegram_account created,
 *                     invitation redeemed
 *   CANCELLED         invitee backed out via /cancel
 * </pre>
 */
public enum BotState {
    PENDING,
    STARTED,
    AWAITING_CONTACT,
    COMPLETED,
    CANCELLED
}
