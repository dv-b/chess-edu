package com.chessedu.platform.identity.model;

/**
 * How an {@link Invitation} is delivered to the invitee.
 *
 * <p>{@code EMAIL} is the original path (a tokenised accept link is emailed).
 * {@code TELEGRAM} routes the invite through the bot: the admin hands the
 * invitee a {@code /start INV_<token>} deeplink, and the bot drives signup
 * (including the Telegram-verified phone capture).
 */
public enum DeliveryChannel {
    EMAIL,
    TELEGRAM
}
