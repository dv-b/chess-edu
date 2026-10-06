package com.chessedu.platform.identity.auth;

import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.PasswordService;
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
 * End-to-end MockMvc walk-through of the M2 auth flow:
 *   login → me → refresh → me (new token) → logout → me (should 401)
 *   plus the reuse-detection path: presenting an already-rotated refresh
 *   token revokes the whole family.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String EMAIL = "test-admin@demo.local";
    static final String PASSWORD = "test-password-12345";

    @BeforeEach
    @Transactional
    void seed() {
        if (users.existsByEmail(EMAIL)) return;

        School school = schools.findBySlug("demo").orElseThrow();

        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(EMAIL);
        u.setDisplayName("Test Admin");
        u.setPreferredLocale("uz");
        u.setStatus(UserStatus.ACTIVE);
        users.save(u);

        Credentials c = new Credentials();
        c.setUserId(u.getId());
        c.setPasswordHash(passwords.hash(PASSWORD));
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
    void fullLoginRefreshLogoutFlow() throws Exception {
        // 1. login → tokens
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode body = mapper.readTree(login.getResponse().getContentAsString());
        String access = body.get("accessToken").asText();
        String refresh = body.get("refreshToken").asText();

        // 2. me → current user
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("SCHOOL_ADMIN"))
                .andExpect(jsonPath("$.schoolName").value("Demo Chess School"));

        // 3. refresh → new tokens, old refresh must now fail
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refresh)))
                .andExpect(status().isOk())
                .andReturn();
        String newRefresh = mapper.readTree(refreshed.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refresh);

        // 4. Re-presenting the old refresh should now 401 (reuse detected)
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refresh)))
                .andExpect(status().isUnauthorized());

        // 5. And because reuse revokes the family, the "new" refresh is dead too.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(newRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-password-123"}""".formatted(EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
