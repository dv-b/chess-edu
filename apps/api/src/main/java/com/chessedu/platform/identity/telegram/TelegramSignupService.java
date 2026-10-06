package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.common.config.WebProperties;
import com.chessedu.platform.identity.auth.AuthService;
import com.chessedu.platform.identity.model.BotState;
import com.chessedu.platform.identity.model.DeliveryChannel;
import com.chessedu.platform.identity.model.Invitation;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.InvitationRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.dto.TgContact;
import com.chessedu.platform.identity.telegram.dto.TgMessage;
import com.chessedu.platform.identity.telegram.dto.TgUser;
import com.chessedu.platform.security.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The bot-driven signup funnel: {@code /start INV_<token>} → "Share Contact" →
 * account created → OTT deeplink back to the web app. Only created when the bot
 * is enabled.
 *
 * <h2>Why an in-memory correlation map</h2>
 * A "Share Contact" message carries no invitation token, so we must remember
 * which invitation the sender is mid-signup for. We hold that in
 * {@link #awaitingContact} (telegramId → invitationId). {@code bot_state} on the
 * invitation row is persisted purely for the admin funnel display; this map is
 * the source of truth for "which invitation is this contact answering". A
 * restart mid-conversation just means the user re-runs {@code /start} (the bot
 * tells them to). Single-instance only for v1 — a multi-instance deployment
 * would move this correlation (and the poll offset) into Redis. Documented as a
 * scope-out.
 *
 * <h2>What gets created</h2>
 * On a successful contact share: a {@link User} (email + displayName + role from
 * the invitation; <em>no</em> {@code Credentials} — Telegram users have no
 * password), a {@link Membership} (or {@code platformRole} for a platform
 * admin), a {@link TelegramAccount} (profile snapshot + verified phone +
 * chat_id), the invitation is redeemed, and an OTT is minted for the web
 * handoff. Mirrors {@code InvitationService.accept} minus the credentials step.
 */
@Service
@ConditionalOnProperty(prefix = "chessedu.telegram", name = "enabled", havingValue = "true")
public class TelegramSignupService {

    private static final Logger log = LoggerFactory.getLogger(TelegramSignupService.class);

    private static final String START_PREFIX = "INV_";
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    /** Same E.164 shape the telegram_account CHECK constraint enforces. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,15}$");

    /** telegramId → invitationId for users mid-signup, awaiting the contact share. */
    private final ConcurrentHashMap<Long, UUID> awaitingContact = new ConcurrentHashMap<>();

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final TelegramAccountRepository telegramAccounts;
    private final TelegramAuthTokenService ottService;
    private final TelegramApiClient api;
    private final TokenHasher hasher;
    private final WebProperties webProps;

    public TelegramSignupService(InvitationRepository invitations,
                                 UserRepository users,
                                 MembershipRepository memberships,
                                 TelegramAccountRepository telegramAccounts,
                                 TelegramAuthTokenService ottService,
                                 TelegramApiClient api,
                                 TokenHasher hasher,
                                 WebProperties webProps) {
        this.invitations = invitations;
        this.users = users;
        this.memberships = memberships;
        this.telegramAccounts = telegramAccounts;
        this.ottService = ottService;
        this.api = api;
        this.hasher = hasher;
        this.webProps = webProps;
    }

    // ========================================================================
    // /start INV_<token>
    // ========================================================================

    @Transactional
    public void handleStart(TgMessage msg, String payload) {
        TgUser from = msg.from();
        long tgId = from.id();
        long chatId = msg.chat().id();
        String locale = TelegramBotMessages.resolveLocale(from.languageCode());

        // Repeat sign-in goes through the web Login Widget, not the bot.
        if (telegramAccounts.existsByTelegramId(tgId)) {
            api.sendMessage(chatId, TelegramBotMessages.alreadyLinked(locale, webProps.baseUrl()));
            return;
        }
        if (payload == null || !payload.startsWith(START_PREFIX)) {
            api.sendMessage(chatId, TelegramBotMessages.startInvalidToken(locale));
            return;
        }

        String rawToken = payload.substring(START_PREFIX.length());
        Invitation invite = invitations.findByTokenHash(hasher.hash(rawToken)).orElse(null);
        Instant now = Instant.now();

        if (invite == null) {
            api.sendMessage(chatId, TelegramBotMessages.startInvalidToken(locale));
            return;
        }
        if (invite.getDeliveryChannel() != DeliveryChannel.TELEGRAM) {
            api.sendMessage(chatId, TelegramBotMessages.startNotTelegram(locale));
            return;
        }
        if (invite.isAccepted()) {
            api.sendMessage(chatId, TelegramBotMessages.startAlreadyAccepted(locale));
            return;
        }
        if (invite.isExpired(now)) {
            api.sendMessage(chatId, TelegramBotMessages.startExpired(locale));
            return;
        }
        // Telegram auth is for non-student roles only (defence in depth — the
        // issue path already refuses STUDENT).
        if (Role.STUDENT.name().equals(invite.getRole())) {
            api.sendMessage(chatId, TelegramBotMessages.startNotTelegram(locale));
            return;
        }

        invite.setBotState(BotState.AWAITING_CONTACT);
        invitations.save(invite);
        awaitingContact.put(tgId, invite.getId());

        api.sendContactRequest(chatId,
                TelegramBotMessages.askContact(locale, invite.getDisplayName()),
                TelegramBotMessages.shareContactButton(locale));
    }

    // ========================================================================
    // Share Contact
    // ========================================================================

    @Transactional
    public void handleContact(TgMessage msg) {
        TgUser from = msg.from();
        long tgId = from.id();
        long chatId = msg.chat().id();
        String locale = TelegramBotMessages.resolveLocale(from.languageCode());

        UUID invitationId = awaitingContact.get(tgId);
        if (invitationId == null) {
            api.sendMessage(chatId, TelegramBotMessages.contactWithoutStart(locale));
            return;
        }

        TgContact contact = msg.contact();
        // Reject sharing someone else's contact card.
        if (contact == null || contact.userId() == null || contact.userId() != tgId) {
            api.sendMessage(chatId, TelegramBotMessages.contactNotOwn(locale));
            return;
        }

        Instant now = Instant.now();
        Invitation invite = invitations.findById(invitationId).orElse(null);
        if (invite == null || invite.isAccepted() || invite.isExpired(now)) {
            awaitingContact.remove(tgId);
            api.sendMessage(chatId, TelegramBotMessages.startExpired(locale));
            return;
        }

        String phone = normalizePhone(contact.phoneNumber());
        if (phone == null) {
            awaitingContact.remove(tgId);
            api.sendMessage(chatId, TelegramBotMessages.badPhone(locale));
            return;
        }

        String email = AuthService.normalizeEmail(invite.getEmail());
        if (users.findByEmail(email).isPresent()) {
            awaitingContact.remove(tgId);
            api.sendMessage(chatId, TelegramBotMessages.emailConflict(locale));
            return;
        }

        boolean isPlatformAdmin = PLATFORM_ADMIN_ROLE.equals(invite.getRole());

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(invite.getDisplayName());
        user.setPreferredLocale(locale);
        user.setPhone(phone);
        user.setStatus(UserStatus.ACTIVE);
        if (isPlatformAdmin) {
            user.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        }
        user.setLastLoginAt(now);
        users.save(user);

        // Membership only for school-scoped roles (platform admin has none).
        if (!isPlatformAdmin) {
            Membership membership = new Membership();
            membership.setId(UUID.randomUUID());
            membership.setUserId(user.getId());
            membership.setSchoolId(invite.getSchoolId());
            membership.setRole(Role.valueOf(invite.getRole()));
            membership.setStatus(MembershipStatus.ACTIVE);
            memberships.save(membership);
        }

        TelegramAccount acct = new TelegramAccount();
        acct.setId(UUID.randomUUID());
        acct.setUserId(user.getId());
        acct.setTelegramId(tgId);
        acct.setUsername(from.username());
        acct.setFirstName(from.firstName());
        acct.setLastName(from.lastName());
        acct.setPhone(phone);
        acct.setLanguageCode(from.languageCode());
        acct.setChatId(chatId);
        acct.setLinkedAt(now);
        acct.setLastSeenAt(now);
        telegramAccounts.save(acct);

        invite.setAcceptedAt(now);
        invite.setAcceptedUser(user.getId());
        invite.setBotState(BotState.COMPLETED);
        invitations.save(invite);

        awaitingContact.remove(tgId);

        String ott = ottService.mint(user.getId());
        String deeplink = webProps.baseUrl() + "/" + locale + "/auth/accept?ott="
                + URLEncoder.encode(ott, StandardCharsets.UTF_8);
        api.sendMessage(chatId, TelegramBotMessages.signupComplete(locale, deeplink));

        log.info("Telegram signup completed: user={} role={} school={} telegramId={}",
                user.getId(), invite.getRole(), invite.getSchoolId(), tgId);
    }

    // ========================================================================
    // /cancel
    // ========================================================================

    @Transactional
    public void handleCancel(TgMessage msg) {
        TgUser from = msg.from();
        long tgId = from.id();
        long chatId = msg.chat().id();
        String locale = TelegramBotMessages.resolveLocale(from.languageCode());

        UUID invitationId = awaitingContact.remove(tgId);
        if (invitationId == null) {
            api.sendMessage(chatId, TelegramBotMessages.nothingToCancel(locale));
            return;
        }
        invitations.findById(invitationId).ifPresent(inv -> {
            if (!inv.isAccepted()) {
                inv.setBotState(BotState.CANCELLED);
                invitations.save(inv);
            }
        });
        api.sendMessage(chatId, TelegramBotMessages.cancelled(locale));
    }

    // ========================================================================
    // Fallback for any other text
    // ========================================================================

    public void handleHelp(TgMessage msg) {
        TgUser from = msg.from();
        String locale = TelegramBotMessages.resolveLocale(from.languageCode());
        api.sendMessage(msg.chat().id(), TelegramBotMessages.help(locale));
    }

    // ------------------------------------------------------------------------

    /**
     * Normalise a Telegram phone number to E.164 ({@code +<digits>}). Telegram
     * sometimes omits the leading {@code +}. Returns null if the result doesn't
     * match the E.164 shape the DB CHECK enforces (so the caller can fail
     * gracefully rather than hit a 500 on insert).
     */
    static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s()\\-]", "");
        if (!digits.startsWith("+")) {
            digits = "+" + digits;
        }
        return E164.matcher(digits).matches() ? digits : null;
    }
}
