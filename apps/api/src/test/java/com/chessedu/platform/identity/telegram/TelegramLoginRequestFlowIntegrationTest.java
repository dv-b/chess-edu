package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.model.TelegramLoginRequest;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.TelegramLoginRequestRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.TelegramLoginRequestService.CompleteOutcome;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth roadmap item 2: "Log in with Telegram" for an already-linked account,
 * without the Login Widget. The bot isn't running in the test profile, so
 * its one line of work — {@code TelegramLoginRequestService.complete} — is
 * called directly, exactly as {@code TelegramLoginBotHandler} does after it
 * resolves the Telegram id to a user.
 *
 *   POST login-requests → poll = PENDING → bot completes → poll = COMPLETED
 *   with tokens → /me works → second poll 410 → wrong secret 401 →
 *   unknown id 404 → expired 410 → bogus nonce / double completion outcomes.
 */
@SpringBootTest(properties = "chessedu.telegram.bot-username=kingside_test_bot")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramLoginRequestFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired TelegramAccountRepository telegramAccounts;
    @Autowired TelegramLoginRequestRepository loginRequests;
    @Autowired SchoolRepository schools;
    @Autowired TelegramLoginRequestService service;

    static final String EMAIL = "tg-relogin@demo.local";
    static final long TELEGRAM_ID = 123_456_789L;

    User user;

    @BeforeEach
    void seed() {
        user = users.findByEmail(EMAIL).orElseGet(() -> {
            School school = schools.findBySlug("demo").orElseThrow();

            User u = new User();
            u.setId(UUID.randomUUID());
            u.setEmail(EMAIL);
            u.setDisplayName("Relogin Parent");
            u.setPreferredLocale("uz");
            u.setStatus(UserStatus.ACTIVE);
            users.save(u);

            Membership m = new Membership();
            m.setId(UUID.randomUUID());
            m.setUserId(u.getId());
            m.setSchoolId(school.getId());
            m.setRole(Role.PARENT);
            m.setStatus(MembershipStatus.ACTIVE);
            memberships.save(m);

            TelegramAccount ta = new TelegramAccount();
            ta.setId(UUID.randomUUID());
            ta.setUserId(u.getId());
            ta.setTelegramId(TELEGRAM_ID);
            ta.setLinkedAt(Instant.now().minus(1, ChronoUnit.DAYS));
            telegramAccounts.save(ta);
            return u;
        });
    }

    /** What the browser gets back from "Log in with Telegram". */
    record Started(UUID id, String clientSecret, String nonce) {}

    private Started start() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/telegram/login-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.clientSecret").isNotEmpty())
                .andExpect(jsonPath("$.deeplink").value(org.hamcrest.Matchers.startsWith("https://t.me/kingside_test_bot?start=login_")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        String deeplink = body.get("deeplink").asText();
        String nonce = deeplink.substring(deeplink.indexOf("login_") + "login_".length());
        return new Started(UUID.fromString(body.get("id").asText()), body.get("clientSecret").asText(), nonce);
    }

    private MvcResult poll(UUID id, String secret) throws Exception {
        return mvc.perform(post("/api/v1/auth/telegram/login-requests/" + id + "/poll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientSecret":"%s"}""".formatted(secret)))
                .andReturn();
    }

    @Test
    void fullHandshakeIssuesSessionOnce() throws Exception {
        Started s = start();

        // Browser polls before the bot did anything.
        MvcResult pending = poll(s.id(), s.clientSecret());
        assertThat(pending.getResponse().getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(pending.getResponse().getContentAsString()).get("status").asText())
                .isEqualTo("PENDING");

        // Bot: /start login_<nonce> from the linked Telegram account.
        assertThat(service.complete(s.nonce(), TELEGRAM_ID, user.getId())).isEqualTo(CompleteOutcome.COMPLETED);

        // Browser polls again: session issued, request consumed.
        MvcResult done = poll(s.id(), s.clientSecret());
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(done.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
        String access = body.get("tokens").get("accessToken").asText();
        assertThat(body.get("tokens").get("refreshToken").asText()).isNotBlank();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("PARENT"));

        TelegramLoginRequest row = loginRequests.findById(s.id()).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(user.getId());
        assertThat(row.getTelegramId()).isEqualTo(TELEGRAM_ID);
        assertThat(row.getConsumedAt()).isNotNull();
        assertThat(telegramAccounts.findByUserId(user.getId()).orElseThrow().getLastSeenAt()).isNotNull();

        // Single-use: a third poll is 410.
        assertThat(poll(s.id(), s.clientSecret()).getResponse().getStatus()).isEqualTo(410);

        // And the bot can't confirm the same nonce twice.
        assertThat(service.complete(s.nonce(), TELEGRAM_ID, user.getId())).isEqualTo(CompleteOutcome.ALREADY_COMPLETED);
    }

    @Test
    void wrongClientSecretIsRejectedEvenAfterCompletion() throws Exception {
        Started s = start();
        service.complete(s.nonce(), TELEGRAM_ID, user.getId());

        assertThat(poll(s.id(), "not-the-secret").getResponse().getStatus()).isEqualTo(401);
        // …and the request is still intact for the real browser.
        assertThat(poll(s.id(), s.clientSecret()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownRequestIs404() throws Exception {
        assertThat(poll(UUID.randomUUID(), "whatever").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void expiredRequestIsGoneForBothSides() throws Exception {
        Started s = start();
        TelegramLoginRequest row = loginRequests.findById(s.id()).orElseThrow();
        row.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        loginRequests.save(row);

        assertThat(service.complete(s.nonce(), TELEGRAM_ID, user.getId())).isEqualTo(CompleteOutcome.EXPIRED);
        assertThat(poll(s.id(), s.clientSecret()).getResponse().getStatus()).isEqualTo(410);
    }

    @Test
    void bogusNonceIsNotFound() {
        assertThat(service.complete("no-such-nonce", TELEGRAM_ID, user.getId())).isEqualTo(CompleteOutcome.NOT_FOUND);
    }
}
