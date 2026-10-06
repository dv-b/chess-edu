package com.chessedu.platform.identity.invitation;

import com.chessedu.platform.identity.model.Credentials;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end happy-path invitation flow plus the two interesting failure modes:
 * replaying an accept, and forbidden cross-school actions.
 *
 * <ul>
 *   <li>Admin issues an invitation → 200 with the invite view.</li>
 *   <li>Preview (public) → returns role/email/school.</li>
 *   <li>Accept with the raw token + password → creates user, auto-logs in.</li>
 *   <li>Second accept with the same token → 410 Gone.</li>
 *   <li>New user can log in with the password they set.</li>
 *   <li>Admin list shows the accepted invitation with {@code acceptedAt} populated.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(InvitationTestSupport.class)
class InvitationFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;
    @Autowired InvitationRepository invitations;
    @Autowired TokenHasher hasher;
    @Autowired InvitationTestSupport.MintedTokenRegistry mintedTokens;

    static final String ADMIN_EMAIL = "invite-admin@demo.local";
    static final String ADMIN_PASSWORD = "test-password-12345";
    static final String INVITEE_EMAIL = "new-teacher@demo.local";
    static final String INVITEE_PASSWORD = "brand-new-secret-42";

    @BeforeEach
    @Transactional
    void seed() {
        // Clean any leftovers from a previous run so we can assert on counts.
        users.findByEmail(INVITEE_EMAIL).ifPresent(u -> {
            memberships.findByUserId(u.getId()).ifPresent(memberships::delete);
            credentials.findByUserId(u.getId()).ifPresent(credentials::delete);
            users.delete(u);
        });
        invitations.findAll().stream()
                .filter(i -> INVITEE_EMAIL.equalsIgnoreCase(i.getEmail()))
                .forEach(invitations::delete);

        if (users.existsByEmail(ADMIN_EMAIL)) return;

        School school = schools.findBySlug("demo").orElseThrow();

        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(ADMIN_EMAIL);
        u.setDisplayName("Invite Admin");
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
    void issueAcceptLoginFlow() throws Exception {
        String adminAccess = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        // 1. Issue an invitation.
        MvcResult issued = mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "TEACHER",
                                  "email": "%s",
                                  "displayName": "New Teacher",
                                  "preferredLocale": "en"
                                }""".formatted(INVITEE_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(INVITEE_EMAIL))
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.schoolName").value("Demo Chess School"))
                .andExpect(jsonPath("$.acceptedAt").doesNotExist())
                .andReturn();

        UUID invitationId = UUID.fromString(
                mapper.readTree(issued.getResponse().getContentAsString()).get("id").asText());

        // 2. Recover the raw token. Production service hashes before storing;
        //    the test support's spy TokenHasher records (hash → raw) pairs so
        //    we can pull it back out here.
        String rawToken = extractRawTokenFor(invitationId);

        // 3. Preview (public, no auth).
        mvc.perform(get("/api/v1/auth/invitations/" + rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(INVITEE_EMAIL))
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.schoolName").value("Demo Chess School"));

        // 4. Accept (public, no auth) → 200 + tokens.
        MvcResult accepted = mvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "password": "%s",
                                  "preferredLocale": "uz"
                                }""".formatted(rawToken, INVITEE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        JsonNode acceptBody = mapper.readTree(accepted.getResponse().getContentAsString());
        assertThat(acceptBody.get("accessToken").asText()).isNotEmpty();

        // 5. Second accept with the same token → 410 Gone.
        mvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "%s",
                                  "password": "%s"
                                }""".formatted(rawToken, INVITEE_PASSWORD)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));

        // 6. The invitee can now log in with their password.
        String teacherAccess = login(INVITEE_EMAIL, INVITEE_PASSWORD);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + teacherAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.schoolName").value("Demo Chess School"));

        // 7. Admin list: shows the invitation as accepted.
        mvc.perform(get("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(INVITEE_EMAIL))
                .andExpect(jsonPath("$[0].acceptedAt").isNotEmpty());
    }

    @Test
    void nonAdminCannotIssueInvitations() throws Exception {
        // Create a TEACHER user and confirm /admin/invitations is forbidden.
        School school = schools.findBySlug("demo").orElseThrow();
        String teacherEmail = "plain-teacher@demo.local";
        String teacherPassword = "teacher-password-12";
        if (!users.existsByEmail(teacherEmail)) {
            User u = new User();
            u.setId(UUID.randomUUID());
            u.setEmail(teacherEmail);
            u.setDisplayName("Plain Teacher");
            u.setPreferredLocale("uz");
            u.setStatus(UserStatus.ACTIVE);
            users.save(u);

            Credentials c = new Credentials();
            c.setUserId(u.getId());
            c.setPasswordHash(passwords.hash(teacherPassword));
            c.setPasswordChangedAt(Instant.now());
            credentials.save(c);

            Membership m = new Membership();
            m.setId(UUID.randomUUID());
            m.setUserId(u.getId());
            m.setSchoolId(school.getId());
            m.setRole(Role.TEACHER);
            m.setStatus(MembershipStatus.ACTIVE);
            memberships.save(m);
        }

        String access = login(teacherEmail, teacherPassword);

        mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"TEACHER","email":"x@demo.local","displayName":"X"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokingAcceptedInvitationReturns410() throws Exception {
        String adminAccess = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        // Issue + accept so we have an invitation in "accepted" state.
        MvcResult issued = mvc.perform(post("/api/v1/admin/invitations")
                        .header("Authorization", "Bearer " + adminAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"TEACHER","email":"%s","displayName":"NT"}"""
                                .formatted(INVITEE_EMAIL)))
                .andExpect(status().isOk())
                .andReturn();
        UUID invitationId = UUID.fromString(
                mapper.readTree(issued.getResponse().getContentAsString()).get("id").asText());
        String rawToken = extractRawTokenFor(invitationId);
        mvc.perform(post("/api/v1/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"%s"}"""
                                .formatted(rawToken, INVITEE_PASSWORD)))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/admin/invitations/" + invitationId)
                        .header("Authorization", "Bearer " + adminAccess))
                .andExpect(status().isGone());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String extractRawTokenFor(UUID invitationId) {
        String hash = invitations.findById(invitationId)
                .orElseThrow(() -> new AssertionError("invitation row not found: " + invitationId))
                .getTokenHash();
        String raw = mintedTokens.byHash(hash);
        assertThat(raw)
                .as("raw invitation token should be captured for id %s", invitationId)
                .isNotBlank();
        return raw;
    }
}
