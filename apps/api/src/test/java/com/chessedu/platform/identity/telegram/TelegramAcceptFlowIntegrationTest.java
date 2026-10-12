package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.model.TelegramAuthToken;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.TelegramAuthTokenRepository;
import com.chessedu.platform.identity.repo.UserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task C: the web-side OTT redemption. The bot (Task B) is not involved —
 * we mint the OTT directly through {@link TelegramAuthTokenService}, exactly
 * as the bot does after a contact share, then walk the deeplink's endpoint:
 *
 *   POST /auth/telegram/accept {ott} → 200 tokens → GET /me works → the OTT
 *   is single-use → garbage / expired OTTs and deactivated users all 401.
 *
 * Runs with the bot worker disabled (test profile default) — proves the
 * redemption path is reachable without a bot token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramAcceptFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired TelegramAccountRepository telegramAccounts;
    @Autowired TelegramAuthTokenRepository ottTokens;
    @Autowired SchoolRepository schools;
    @Autowired TelegramAuthTokenService otts;
    @Autowired TokenHasher hasher;

    static final String EMAIL = "tg-teacher@demo.local";
    static final long TELEGRAM_ID = 987_654_321L;

    User user;

    @BeforeEach
    void seed() {
        user = users.findByEmail(EMAIL).orElseGet(() -> {
            School school = schools.findBySlug("demo").orElseThrow();

            User u = new User();
            u.setId(UUID.randomUUID());
            u.setEmail(EMAIL);
            u.setDisplayName("Telegram Teacher");
            u.setPreferredLocale("ru");
            u.setStatus(UserStatus.ACTIVE);
            users.save(u);

            Membership m = new Membership();
            m.setId(UUID.randomUUID());
            m.setUserId(u.getId());
            m.setSchoolId(school.getId());
            m.setRole(Role.TEACHER);
            m.setStatus(MembershipStatus.ACTIVE);
            memberships.save(m);

            // No Credentials row on purpose — Telegram-signup users have no password.
            TelegramAccount ta = new TelegramAccount();
            ta.setId(UUID.randomUUID());
            ta.setUserId(u.getId());
            ta.setTelegramId(TELEGRAM_ID);
            ta.setFirstName("Telegram");
            ta.setPhone("+998901234567");
            ta.setLinkedAt(Instant.now().minus(1, ChronoUnit.DAYS));
            telegramAccounts.save(ta);
            return u;
        });
        // Other tests in this class may have deactivated the user.
        if (user.getStatus() != UserStatus.ACTIVE) {
            user.setStatus(UserStatus.ACTIVE);
            users.save(user);
        }
    }

    @Test
    void acceptRedeemsOttForSessionAndIsSingleUse() throws Exception {
        String ott = otts.mint(user.getId());
        Instant before = Instant.now();

        MvcResult accepted = mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "ott-test-agent")
                        .content("""
                                {"ott":"%s"}""".formatted(ott)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode body = mapper.readTree(accepted.getResponse().getContentAsString());
        String access = body.get("accessToken").asText();
        String refresh = body.get("refreshToken").asText();

        // The session is a real one: /me resolves the user + membership…
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.schoolName").value("Demo Chess School"));

        // …and the refresh token rotates like any other.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // Side effects: OTT stamped used, last-login + last-seen bumped.
        TelegramAuthToken row = ottTokens.findByTokenHash(hasher.hash(ott)).orElseThrow();
        assertThat(row.getUsedAt()).isNotNull();
        assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isAfterOrEqualTo(before);
        assertThat(telegramAccounts.findByUserId(user.getId()).orElseThrow().getLastSeenAt())
                .isAfterOrEqualTo(before);

        // Single-use: presenting the same OTT again is rejected.
        mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ott":"%s"}""".formatted(ott)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownOttReturns401() throws Exception {
        mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ott":"not-a-real-token"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void blankOttFailsValidation() throws Exception {
        mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ott":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredOttReturns401() throws Exception {
        String ott = otts.mint(user.getId());
        TelegramAuthToken row = ottTokens.findByTokenHash(hasher.hash(ott)).orElseThrow();
        row.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        ottTokens.save(row);

        mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ott":"%s"}""".formatted(ott)))
                .andExpect(status().isUnauthorized());

        // Not stamped used — the rejection happened before redemption.
        assertThat(ottTokens.findByTokenHash(hasher.hash(ott)).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    void deactivatedUserCannotRedeem() throws Exception {
        String ott = otts.mint(user.getId());
        user.setStatus(UserStatus.SUSPENDED);
        users.save(user);

        mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ott":"%s"}""".formatted(ott)))
                .andExpect(status().isUnauthorized());

        // The whole accept is one transaction: the used-stamp rolled back
        // with the failed session issuance.
        assertThat(ottTokens.findByTokenHash(hasher.hash(ott)).orElseThrow().getUsedAt()).isNull();
    }
}
