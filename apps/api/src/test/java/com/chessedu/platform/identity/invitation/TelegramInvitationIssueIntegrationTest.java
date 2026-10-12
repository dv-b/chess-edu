package com.chessedu.platform.identity.invitation;

import com.chessedu.platform.identity.model.BotState;
import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.DeliveryChannel;
import com.chessedu.platform.identity.model.Invitation;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.InvitationRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.PasswordService;
import com.chessedu.platform.security.TokenHasher;
import com.chessedu.platform.tenancy.School;
import com.chessedu.platform.tenancy.SchoolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task D backend half: issuing an invitation with {@code deliveryChannel=TELEGRAM}.
 * No email goes out; instead the issue response carries the bot deeplink
 * (once), the row starts in {@code bot_state=PENDING}, and the list view
 * exposes channel + funnel state so the admin UI can render them.
 *
 * Pins a bot username so the deeplink can be built — the default test
 * profile leaves it blank, which makes TELEGRAM issuance a 400.
 */
@SpringBootTest(properties = "chessedu.telegram.bot-username=kingside_test_bot")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramInvitationIssueIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;
    @Autowired InvitationRepository invitations;
    @Autowired TokenHasher hasher;

    static final String ADMIN_EMAIL = "tg-invite-admin@demo.local";
    static final String ADMIN_PASSWORD = "test-password-12345";
    static final String INVITEE_EMAIL = "tg-invitee@demo.local";

    @BeforeEach
    @Transactional
    void seed() {
        invitations.findAll().stream()
                .filter(i -> INVITEE_EMAIL.equalsIgnoreCase(i.getEmail()))
                .forEach(invitations::delete);

        if (users.existsByEmail(ADMIN_EMAIL)) return;

        School school = schools.findBySlug("demo").orElseThrow();

        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(ADMIN_EMAIL);
        u.setDisplayName("TG Invite Admin");
        u.setPreferredLocale("uz");
        u.setStatus(UserStatus.ACTIVE);
        users.save(u);

        Credentials c = new Credentials();
        c.setUserId(u.getId());
        c.setPasswordHash(passwords.hash(ADMIN_PASSWORD));
        c.setPasswordChangedAt(Instant.now());
        credentials.save(c);

        Membership m = new Membership();
        m.setId(UUID.randomUUID());
        m.setUserId(u.getId());
        m.setSchoolId(school.getId());
        m.setRole(Role.SCHOOL_ADMIN);
        m.setStatus(MembershipStatus.ACTIVE);
        memberships.save(m);
    }

    @Test
    void telegramInviteReturnsDeeplinkAndStartsPending() throws Exception {
        String adminAccess = login();

        MvcResult issued = mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "TEACHER",
                                  "email": "%s",
                                  "displayName": "TG Invitee",
                                  "preferredLocale": "ru",
                                  "deliveryChannel": "TELEGRAM"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryChannel").value("TELEGRAM"))
                .andExpect(jsonPath("$.botState").value("PENDING"))
                .andExpect(jsonPath("$.botDeeplink").isNotEmpty())
                .andReturn();

        JsonNode body = mapper.readTree(issued.getResponse().getContentAsString());
        String deeplink = body.get("botDeeplink").asText();
        assertThat(deeplink).startsWith("https://t.me/kingside_test_bot?start=INV_");

        // The token inside the deeplink is the invitation's own: hashing it
        // finds the row — i.e. the bot's /start INV_<token> will resolve.
        String rawToken = deeplink.substring(deeplink.indexOf("INV_") + 4);
        Invitation row = invitations.findByTokenHash(hasher.hash(rawToken)).orElseThrow();
        assertThat(row.getDeliveryChannel()).isEqualTo(DeliveryChannel.TELEGRAM);
        assertThat(row.getBotState()).isEqualTo(BotState.PENDING);
        assertThat(row.getEmail()).isEqualTo(INVITEE_EMAIL);

        // The list view carries channel + state, but never the deeplink.
        MvcResult list = mvc.perform(get("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ours = null;
        for (JsonNode n : mapper.readTree(list.getResponse().getContentAsString())) {
            if (INVITEE_EMAIL.equals(n.path("email").asText())) ours = n;
        }
        assertThat(ours).isNotNull();
        assertThat(ours.path("deliveryChannel").asText()).isEqualTo("TELEGRAM");
        assertThat(ours.path("botState").asText()).isEqualTo("PENDING");
        assertThat(ours.has("botDeeplink")).isFalse();
    }

    @Test
    void emailInviteIsUnchangedByTheNewField() throws Exception {
        String adminAccess = login();

        mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "TEACHER",
                                  "email": "%s",
                                  "displayName": "TG Invitee"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryChannel").value("EMAIL"))
                .andExpect(jsonPath("$.botState").doesNotExist())
                .andExpect(jsonPath("$.botDeeplink").doesNotExist());
    }

    /** Telegram doesn't loosen the existing rule: students are not invite-eligible on any channel. */
    @Test
    void telegramInviteForStudentIsRejected() throws Exception {
        String adminAccess = login();

        mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "STUDENT",
                                  "email": "%s",
                                  "displayName": "TG Invitee",
                                  "deliveryChannel": "TELEGRAM"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isForbidden());
    }

    @Test
    void telegramInviteWithPhoneIsNormalisedAndCarriesBotUsername() throws Exception {
        String adminAccess = login();

        MvcResult issued = mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "PARENT",
                                  "email": "%s",
                                  "displayName": "TG Invitee",
                                  "deliveryChannel": "TELEGRAM",
                                  "phone": "998 (90) 555-66-77"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+998905556677"))
                .andExpect(jsonPath("$.botUsername").value("kingside_test_bot"))
                .andExpect(jsonPath("$.botDeeplink").isNotEmpty())
                .andReturn();
        UUID id = UUID.fromString(mapper.readTree(issued.getResponse().getContentAsString()).get("id").asText());
        assertThat(invitations.findById(id).orElseThrow().getPhone()).isEqualTo("+998905556677");

        // A second pending invitation for the same number is refused (even for another email).
        mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "PARENT",
                                  "email": "tg-invitee-2@demo.local",
                                  "displayName": "Someone Else",
                                  "deliveryChannel": "TELEGRAM",
                                  "phone": "+998905556677"
                                }"""))
                .andExpect(status().isConflict());
        invitations.findAll().stream()
                .filter(i -> "tg-invitee-2@demo.local".equalsIgnoreCase(i.getEmail()))
                .forEach(invitations::delete);
    }

    @Test
    void unparseablePhoneIsRejected() throws Exception {
        String adminAccess = login();
        mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "PARENT",
                                  "email": "%s",
                                  "displayName": "TG Invitee",
                                  "deliveryChannel": "TELEGRAM",
                                  "phone": "call me maybe"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownChannelFailsValidation() throws Exception {
        String adminAccess = login();

        mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "TEACHER",
                                  "email": "%s",
                                  "displayName": "TG Invitee",
                                  "deliveryChannel": "PIGEON"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
