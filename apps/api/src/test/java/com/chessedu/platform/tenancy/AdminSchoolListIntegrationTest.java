package com.chessedu.platform.tenancy;

import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.PasswordService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Platform admins can list schools for pickers; school admins cannot. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSchoolListIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String PLATFORM_EMAIL = "schools-platform@demo.local";
    static final String SCHOOL_EMAIL = "schools-schooladmin@demo.local";
    static final String PASSWORD = "test-password-12345";

    @BeforeEach
    void seed() {
        if (!users.existsByEmail(PLATFORM_EMAIL)) {
            User u = newUser(PLATFORM_EMAIL, "Platform");
            u.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
            users.save(u);
            saveCredentials(u);
        }
        if (!users.existsByEmail(SCHOOL_EMAIL)) {
            User u = newUser(SCHOOL_EMAIL, "School Admin");
            users.save(u);
            saveCredentials(u);
            School school = schools.findBySlug("demo").orElseThrow();
            Membership m = new Membership();
            m.setId(UUID.randomUUID());
            m.setUserId(u.getId());
            m.setSchoolId(school.getId());
            m.setRole(Role.SCHOOL_ADMIN);
            m.setStatus(MembershipStatus.ACTIVE);
            memberships.save(m);
        }
    }

    private User newUser(String email, String name) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(name);
        u.setPreferredLocale("en");
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    private void saveCredentials(User u) {
        Credentials c = new Credentials();
        c.setUserId(u.getId());
        c.setPasswordHash(passwords.hash(PASSWORD));
        c.setPasswordChangedAt(Instant.now());
        credentials.save(c);
    }

    private String login(String email) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void platformAdminSeesEverySchool() throws Exception {
        mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + login(PLATFORM_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'demo')]").isNotEmpty())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].displayName").isNotEmpty());
    }

    @Test
    void schoolAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + login(SCHOOL_EMAIL)))
                .andExpect(status().isForbidden());
    }
}
