package com.chessedu.platform.identity.member;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the read-only members endpoint: happy path with three seeded
 * members, a role filter that narrows the list, and a search filter that
 * matches by displayName fragment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberDirectoryIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "members-admin@demo.local";
    static final String ADMIN_PASSWORD = "test-password-12345";
    static final String TEACHER_EMAIL = "members-teacher@demo.local";
    static final String PARENT_EMAIL  = "members-parent@demo.local";

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        ensureUserWithMembership(ADMIN_EMAIL,   "Members Admin",   Role.SCHOOL_ADMIN, school.getId(), true);
        ensureUserWithMembership(TEACHER_EMAIL, "Lola Tursunova",  Role.TEACHER,      school.getId(), false);
        ensureUserWithMembership(PARENT_EMAIL,  "Aziz Karimov",    Role.PARENT,       school.getId(), false);
    }

    @Test
    void listsAllMembersForSchool() throws Exception {
        String access = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mvc.perform(get("/api/v1/admin/members").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                // three seeded here; the base profile may also seed additional
                // demo members, so we assert on presence rather than exact count
                .andExpect(jsonPath("$[?(@.email == '" + ADMIN_EMAIL + "')]").exists())
                .andExpect(jsonPath("$[?(@.email == '" + TEACHER_EMAIL + "')]").exists())
                .andExpect(jsonPath("$[?(@.email == '" + PARENT_EMAIL + "')]").exists());
    }

    @Test
    void filtersByRole() throws Exception {
        String access = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mvc.perform(get("/api/v1/admin/members?role=TEACHER")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + TEACHER_EMAIL + "')]").exists())
                .andExpect(jsonPath("$[?(@.email == '" + PARENT_EMAIL + "')]").doesNotExist());
    }

    @Test
    void filtersBySearchFragment() throws Exception {
        String access = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mvc.perform(get("/api/v1/admin/members?search=lola")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + TEACHER_EMAIL + "')]").exists())
                .andExpect(jsonPath("$[?(@.email == '" + PARENT_EMAIL + "')]").doesNotExist());
    }

    @Test
    void teacherCannotListMembers() throws Exception {
        // The TEACHER we seeded has no password, so set one and log them in.
        String teacherPassword = "teacher-pass-99";
        User t = users.findByEmail(TEACHER_EMAIL).orElseThrow();
        credentials.findByUserId(t.getId()).ifPresentOrElse(c -> {
            c.setPasswordHash(passwords.hash(teacherPassword));
            c.setPasswordChangedAt(Instant.now());
            credentials.save(c);
        }, () -> {
            Credentials c = new Credentials();
            c.setUserId(t.getId());
            c.setPasswordHash(passwords.hash(teacherPassword));
            c.setPasswordChangedAt(Instant.now());
            credentials.save(c);
        });

        String access = login(TEACHER_EMAIL, teacherPassword);

        mvc.perform(get("/api/v1/admin/members").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private void ensureUserWithMembership(String email, String displayName, Role role,
                                          UUID schoolId, boolean setPassword) {
        if (users.existsByEmail(email)) return;

        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(displayName);
        u.setPreferredLocale("uz");
        u.setStatus(UserStatus.ACTIVE);
        users.save(u);

        if (setPassword) {
            Credentials c = new Credentials();
            c.setUserId(u.getId());
            c.setPasswordHash(passwords.hash(ADMIN_PASSWORD));
            c.setPasswordChangedAt(Instant.now());
            credentials.save(c);
        }

        Membership m = new Membership();
        m.setId(UUID.randomUUID());
        m.setUserId(u.getId());
        m.setSchoolId(schoolId);
        m.setRole(role);
        m.setStatus(MembershipStatus.ACTIVE);
        memberships.save(m);
    }

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
}
