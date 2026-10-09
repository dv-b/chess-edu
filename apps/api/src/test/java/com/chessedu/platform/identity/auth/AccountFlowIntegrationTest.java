package com.chessedu.platform.identity.auth;

import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.TelegramAuthTokenService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth roadmap item 3: a Telegram-signup user (no Credentials row) gets a
 * session via OTT, picks a username and sets a first password on the
 * account endpoints, then signs in with username + password like anyone
 * else. Plus the guard rails: current-password check on change, username
 * uniqueness (case-insensitive), pattern validation, and that /me reports
 * hasPassword / telegramLinked / username.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired TelegramAccountRepository telegramAccounts;
    @Autowired SchoolRepository schools;
    @Autowired TelegramAuthTokenService otts;

    static final String EMAIL = "tg-account@demo.local";
    static final String OTHER_EMAIL = "tg-account-other@demo.local";
    static final long TELEGRAM_ID = 555_000_111L;
    static final String PASSWORD = "first-password-123";
    static final String NEW_PASSWORD = "second-password-456";

    User user;
    User other;

    @BeforeEach
    void seed() {
        // Fresh slate for the credentials-less user so every test starts without a password.
        users.findByEmail(EMAIL).ifPresent(u -> credentials.findByUserId(u.getId()).ifPresent(credentials::delete));
        user = users.findByEmail(EMAIL).orElseGet(() -> seedUser(EMAIL, "Account Tester", TELEGRAM_ID));
        user.setUsername(null);
        users.save(user);
        other = users.findByEmail(OTHER_EMAIL).orElseGet(() -> seedUser(OTHER_EMAIL, "Other Tester", null));
        other.setUsername("taken.name");
        users.save(other);
    }

    private User seedUser(String email, String name, Long telegramId) {
        School school = schools.findBySlug("demo").orElseThrow();
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(name);
        u.setPreferredLocale("uz");
        u.setStatus(UserStatus.ACTIVE);
        users.save(u);

        Membership m = new Membership();
        m.setId(UUID.randomUUID());
        m.setUserId(u.getId());
        m.setSchoolId(school.getId());
        m.setRole(Role.TEACHER);
        m.setStatus(MembershipStatus.ACTIVE);
        memberships.save(m);

        if (telegramId != null) {
            TelegramAccount ta = new TelegramAccount();
            ta.setId(UUID.randomUUID());
            ta.setUserId(u.getId());
            ta.setTelegramId(telegramId);
            ta.setLinkedAt(Instant.now());
            telegramAccounts.save(ta);
        }
        return u;
    }

    /** Session the way a Telegram user gets one: OTT → accept. */
    private String telegramSession() throws Exception {
        String ott = otts.mint(user.getId());
        MvcResult res = mvc.perform(post("/api/v1/auth/telegram/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ott":"%s"}""".formatted(ott)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private MvcResult putJson(String path, String access, String json) throws Exception {
        return mvc.perform(put(path)
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult login(String identifier, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"%s","password":"%s"}""".formatted(identifier, password)))
                .andReturn();
    }

    @Test
    void telegramUserGetsUsernameAndPasswordThenLogsInWithThem() throws Exception {
        String access = telegramSession();

        // /me before: no username, no password, Telegram linked.
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.telegramLinked").value(true));

        // Password login is impossible yet (no credentials row) — opaque 401.
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(401);

        // Pick a username (mixed case + whitespace → normalised).
        MvcResult u = putJson("/api/v1/auth/account/username", access, """
                {"username":"  Account.Tester_1 "}""");
        assertThat(u.getResponse().getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(u.getResponse().getContentAsString()).get("username").asText())
                .isEqualTo("account.tester_1");

        // First password: no currentPassword needed.
        MvcResult p = putJson("/api/v1/auth/account/password", access, """
                {"newPassword":"%s"}""".formatted(PASSWORD));
        assertThat(p.getResponse().getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(p.getResponse().getContentAsString()).get("hasPassword").asBoolean()).isTrue();

        // Now both identifiers work with the password.
        MvcResult byUsername = login("Account.Tester_1", PASSWORD);
        assertThat(byUsername.getResponse().getStatus()).isEqualTo(200);
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);

        // The pre-V014 field name still works too.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk());

        // Change requires the current password.
        assertThat(putJson("/api/v1/auth/account/password", access, """
                {"currentPassword":"wrong-wrong-wrong","newPassword":"%s"}""".formatted(NEW_PASSWORD))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(putJson("/api/v1/auth/account/password", access, """
                {"newPassword":"%s"}""".formatted(NEW_PASSWORD))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(putJson("/api/v1/auth/account/password", access, """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(PASSWORD, NEW_PASSWORD))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(login("account.tester_1", PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("account.tester_1", NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void usernameMustBeUniqueCaseInsensitive() throws Exception {
        String access = telegramSession();
        assertThat(putJson("/api/v1/auth/account/username", access, """
                {"username":"Taken.Name"}""").getResponse().getStatus()).isEqualTo(409);
        // Re-saving your own username is fine.
        assertThat(putJson("/api/v1/auth/account/username", access, """
                {"username":"mine_1"}""").getResponse().getStatus()).isEqualTo(200);
        assertThat(putJson("/api/v1/auth/account/username", access, """
                {"username":"MINE_1"}""").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void usernamePatternIsEnforced() throws Exception {
        String access = telegramSession();
        for (String bad : new String[]{"ab", "has space", "with@at", ".leadingdot", "кириллица", "x".repeat(33)}) {
            MvcResult r = putJson("/api/v1/auth/account/username", access, """
                    {"username":"%s"}""".formatted(bad));
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
        }
    }

    @Test
    void accountEndpointsRequireASession() throws Exception {
        mvc.perform(put("/api/v1/auth/account/username")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"nobody"}"""))
                .andExpect(status().isUnauthorized());
    }
}
