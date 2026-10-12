package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.model.BotState;
import com.chessedu.platform.identity.model.DeliveryChannel;
import com.chessedu.platform.identity.model.Invitation;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.InvitationRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.dto.TgChat;
import com.chessedu.platform.identity.telegram.dto.TgContact;
import com.chessedu.platform.identity.telegram.dto.TgMessage;
import com.chessedu.platform.identity.telegram.dto.TgUser;
import com.chessedu.platform.security.TokenHasher;
import com.chessedu.platform.tenancy.School;
import com.chessedu.platform.tenancy.SchoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phone-matched Telegram signup (V017): no token link. The admin recorded
 * the invitee's phone; the invitee sends a bare /start, shares their contact,
 * and the bot finds the invitation by phone. Also: a token invitation that
 * carries a phone must be redeemed by that phone.
 *
 * <p>Runs the bot-side beans (enabled=true) with the HTTP client mocked. The
 * worker's scheduled poll gets an empty update list.
 */
@SpringBootTest(properties = {
        "chessedu.telegram.enabled=true",
        "chessedu.telegram.bot-username=kingside_test_bot",
        "chessedu.telegram.bot-token=test-token",
        "chessedu.telegram.poll-delay-ms=3600000"
})
@ActiveProfiles("test")
class TelegramPhoneSignupIntegrationTest {

    @Autowired TelegramSignupService signup;
    @Autowired InvitationRepository invitations;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired TelegramAccountRepository telegramAccounts;
    @Autowired SchoolRepository schools;
    @Autowired TokenHasher hasher;

    @MockBean TelegramApiClient api;

    static final long TG_ID = 424_242_001L;
    static final String PHONE = "+998901230099";
    static final String EMAIL = "phone-signup@demo.local";

    School school;

    @BeforeEach
    void seed() {
        when(api.getUpdates(anyLong())).thenReturn(List.of());
        school = schools.findBySlug("demo").orElseThrow();
        // Clean slate: no user, no telegram link, no invitation for this test identity.
        telegramAccounts.findByTelegramId(TG_ID).ifPresent(telegramAccounts::delete);
        users.findByEmail(EMAIL).ifPresent(u -> {
            memberships.findByUserId(u.getId()).ifPresent(memberships::delete);
            users.delete(u);
        });
        invitations.findAll().stream()
                .filter(i -> EMAIL.equalsIgnoreCase(i.getEmail()))
                .forEach(invitations::delete);
        clearInvocations(api);
    }

    private Invitation invite(String phone, String rawTokenOrNull) {
        Invitation inv = new Invitation();
        inv.setId(UUID.randomUUID());
        inv.setSchoolId(school.getId());
        inv.setRole("PARENT");
        inv.setEmail(EMAIL);
        inv.setDisplayName("Phone Parent");
        inv.setTokenHash(hasher.hash(rawTokenOrNull != null ? rawTokenOrNull : hasher.mint()));
        inv.setIssuedAt(Instant.now());
        inv.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        inv.setDeliveryChannel(DeliveryChannel.TELEGRAM);
        inv.setBotState(BotState.PENDING);
        inv.setPhone(phone);
        return invitations.save(inv);
    }

    private static TgMessage text(String text) {
        return new TgMessage(new TgUser(TG_ID, false, "Phone", "Parent", "phoneparent", "ru"),
                new TgChat(TG_ID, "private"), text, null);
    }

    private static TgMessage contact(String phone, Long ownerId) {
        return new TgMessage(new TgUser(TG_ID, false, "Phone", "Parent", "phoneparent", "ru"),
                new TgChat(TG_ID, "private"), null, new TgContact(phone, "Phone", "Parent", ownerId));
    }

    @Test
    void bareStartThenContactMatchesTheInvitationByPhone() {
        Invitation inv = invite(PHONE, null);

        signup.handleStart(text("/start"), null);
        verify(api).sendContactRequest(eq(TG_ID), anyString(), anyString());

        // Telegram shares the number without '+' sometimes.
        signup.handleContact(contact(PHONE.substring(1), TG_ID));

        Invitation after = invitations.findById(inv.getId()).orElseThrow();
        assertThat(after.isAccepted()).isTrue();
        assertThat(after.getBotState()).isEqualTo(BotState.COMPLETED);

        User user = users.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getPhone()).isEqualTo(PHONE);
        assertThat(telegramAccounts.findByTelegramId(TG_ID)).isPresent();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(api).sendMessage(eq(TG_ID), sent.capture());
        assertThat(sent.getValue()).contains("/ru/auth/accept?ott=");
    }

    @Test
    void contactWithNoPendingInvitationIsRefused() {
        signup.handleStart(text("/start"), null);
        signup.handleContact(contact("+998900000000", TG_ID));

        assertThat(users.findByEmail(EMAIL)).isEmpty();
        assertThat(telegramAccounts.findByTelegramId(TG_ID)).isEmpty();
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(api).sendMessage(eq(TG_ID), sent.capture());
        assertThat(sent.getValue()).containsIgnoringCase("нет приглашения");
    }

    @Test
    void tokenInvitationWithPhoneRejectsADifferentNumber() {
        String raw = hasher.mint();
        Invitation inv = invite(PHONE, raw);

        signup.handleStart(text("/start INV_" + raw), "INV_" + raw);
        signup.handleContact(contact("+998909999999", TG_ID));

        assertThat(invitations.findById(inv.getId()).orElseThrow().isAccepted()).isFalse();
        assertThat(users.findByEmail(EMAIL)).isEmpty();
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(api).sendMessage(eq(TG_ID), sent.capture());
        assertThat(sent.getValue()).containsIgnoringCase("другой номер");

        // Still mid-signup: the right number completes it.
        clearInvocations(api);
        signup.handleContact(contact(PHONE, TG_ID));
        assertThat(invitations.findById(inv.getId()).orElseThrow().isAccepted()).isTrue();
    }

    @Test
    void someoneElsesContactCardNeverMatches() {
        invite(PHONE, null);
        signup.handleStart(text("/start"), null);
        signup.handleContact(contact(PHONE, 999L));   // forwarded card: owner != sender
        assertThat(users.findByEmail(EMAIL)).isEmpty();
        verify(api, never()).sendContactRequest(eq(999L), anyString(), anyString());
    }
}
