package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.TelegramAccount;
import com.chessedu.platform.identity.model.TelegramLoginCode;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import com.chessedu.platform.identity.repo.TelegramLoginCodeRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.tenancy.School;
import com.chessedu.platform.tenancy.SchoolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Log in with Telegram" by one-time code (V015). The bot's HTTP client is
 * a mock: the test reads the code out of the message the service asked it
 * to send, exactly what the user would do in their Telegram chat.
 *
 *   request by username/email/phone → one DM with a 6-digit code →
 *   wrong code 401 (counted) → right code → tokens → /me → 410 on reuse;
 *   unknown identifier → 200 + random id that never verifies;
 *   cooldown → same id, no second DM; 5 wrong attempts → 410; expired → 410.
 */
@SpringBootTest(properties = "chessedu.telegram.bot-username=kingside_test_bot")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramLoginCodeFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired TelegramAccountRepository telegramAccounts;
    @Autowired TelegramLoginCodeRepository loginCodes;
    @Autowired SchoolRepository schools;

    /** Stands in for the conditional bot client; captures what the bot would DM. */
    @MockBean TelegramApiClient api;

    static final String EMAIL = "tg-code@demo.local";
    static final String USERNAME = "code.parent";
    static final String PHONE = "+998901112233";
    static final long TELEGRAM_ID = 777_000_111L;
    static final long CHAT_ID = 777_000_111L;
    static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    User user;

    @BeforeEach
    void seed() {
        user = users.findByEmail(EMAIL).orElseGet(() -> {
            School school = schools.findBySlug("demo").orElseThrow();
            User u = new User();
            u.setId(UUID.randomUUID());
            u.setEmail(EMAIL);
            u.setUsername(USERNAME);
            u.setDisplayName("Code Parent");
            u.setPreferredLocale("ru");
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
            ta.setChatId(CHAT_ID);
            ta.setPhone(PHONE);
            ta.setLanguageCode("ru");
            ta.setLinkedAt(Instant.now());
            telegramAccounts.save(ta);
            return u;
        });
        // Each test starts without a pending code (the cooldown would otherwise reuse one).
        loginCodes.findAll().stream().filter(c -> c.getUserId().equals(user.getId())).forEach(loginCodes::delete);
        clearInvocations(api);
    }

    private UUID request(String identifier) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/telegram/login-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s"}""".formatted(identifier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        return UUID.fromString(mapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    /** The code the "user" reads in Telegram: pulled from the captured sendMessage text. */
    private String sentCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(api).sendMessage(org.mockito.ArgumentMatchers.eq(CHAT_ID), text.capture());
        Matcher m = CODE.matcher(text.getValue());
        assertThat(m.find()).as("message contains a 6-digit code").isTrue();
        return m.group(1);
    }

    private MvcResult verifyCode(UUID id, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/telegram/login-codes/" + id + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}""".formatted(code)))
                .andReturn();
    }

    @Test
    void codeByUsernameSignsInOnce() throws Exception {
        UUID id = request("Code.Parent");          // normalised like login
        String code = sentCode();

        // One wrong attempt is counted, not fatal.
        assertThat(verifyCode(id, "000000").getResponse().getStatus()).isEqualTo(401);
        assertThat(loginCodes.findById(id).orElseThrow().getAttempts()).isEqualTo(1);

        // Spaces the user might type ("123 456") are tolerated.
        String spaced = code.substring(0, 3) + " " + code.substring(3);
        MvcResult ok = verifyCode(id, spaced);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(ok.getResponse().getContentAsString());
        String access = body.get("accessToken").asText();
        assertThat(body.get("refreshToken").asText()).isNotBlank();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("PARENT"))
                .andExpect(jsonPath("$.telegramLinked").value(true));

        TelegramLoginCode row = loginCodes.findById(id).orElseThrow();
        assertThat(row.getConsumedAt()).isNotNull();
        assertThat(telegramAccounts.findByUserId(user.getId()).orElseThrow().getLastSeenAt()).isNotNull();

        // Single use.
        assertThat(verifyCode(id, code).getResponse().getStatus()).isEqualTo(410);
    }

    @Test
    void emailAndPhoneResolveTheSameAccount() throws Exception {
        UUID byEmail = request(EMAIL);
        verify(api, times(1)).sendMessage(anyLong(), anyString());
        loginCodes.deleteById(byEmail);               // skip the cooldown for the next request
        clearInvocations(api);

        UUID byPhone = request("998 90 111-22-33");   // digits only, no '+', with separators
        verify(api, times(1)).sendMessage(anyLong(), anyString());
        assertThat(loginCodes.findById(byPhone).orElseThrow().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void unknownIdentifierLooksLikeAHitButNeverVerifies() throws Exception {
        UUID id = request("nobody.here");
        verify(api, never()).sendMessage(anyLong(), anyString());
        assertThat(verifyCode(id, "123456").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void cooldownReusesThePendingCodeWithoutASecondMessage() throws Exception {
        UUID first = request(USERNAME);
        UUID second = request(USERNAME);
        assertThat(second).isEqualTo(first);
        verify(api, times(1)).sendMessage(anyLong(), anyString());
    }

    @Test
    void fiveWrongAttemptsKillTheCode() throws Exception {
        UUID id = request(USERNAME);
        String code = sentCode();
        for (int i = 0; i < TelegramLoginCode.MAX_ATTEMPTS; i++) {
            assertThat(verifyCode(id, "000000").getResponse().getStatus()).isEqualTo(401);
        }
        // Even the right code is refused now.
        assertThat(verifyCode(id, code).getResponse().getStatus()).isEqualTo(410);
    }

    @Test
    void expiredCodeIsGone() throws Exception {
        UUID id = request(USERNAME);
        String code = sentCode();
        TelegramLoginCode row = loginCodes.findById(id).orElseThrow();
        row.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        loginCodes.save(row);
        assertThat(verifyCode(id, code).getResponse().getStatus()).isEqualTo(410);
    }
}
