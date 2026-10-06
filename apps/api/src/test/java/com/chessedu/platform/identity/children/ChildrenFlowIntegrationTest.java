package com.chessedu.platform.identity.children;

import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.ParentLink;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.ParentLinkRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end exercise of the parent → student flow:
 *
 * <ol>
 *   <li>A PARENT with an ACTIVE membership adds a child, gets them back via
 *       the list endpoint, and sees the expected membership + user rows
 *       persisted (role STUDENT, status ACTIVE, scoped to their school).</li>
 *   <li>A SCHOOL_ADMIN, independently, can now see that student in
 *       /api/v1/admin/members?role=STUDENT — confirming the student is a
 *       real, enrollable member of the school.</li>
 *   <li>Non-parents (teachers) are rejected.</li>
 *   <li>Unlink removes the parent_link but keeps the student + membership
 *       intact for the school to manage.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChildrenFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired ParentLinkRepository parentLinks;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String PARENT_EMAIL  = "children-parent@demo.local";
    static final String TEACHER_EMAIL = "children-teacher@demo.local";
    static final String ADMIN_EMAIL   = "children-admin@demo.local";
    static final String PASSWORD      = "test-password-12345";

    UUID parentId;
    UUID schoolId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        schoolId = school.getId();
        parentId = ensureUserWithMembership(PARENT_EMAIL,  "Aziza Parent",    Role.PARENT,       schoolId);
        ensureUserWithMembership(TEACHER_EMAIL,            "Teacher Probe",   Role.TEACHER,      schoolId);
        ensureUserWithMembership(ADMIN_EMAIL,              "Admin Probe",     Role.SCHOOL_ADMIN, schoolId);
    }

    @Test
    void parentCanAddListAndUnlinkChild() throws Exception {
        String token = login(PARENT_EMAIL);

        // --- 1. Empty list to start with -----------------------------------
        mvc.perform(get("/api/v1/parent/children").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // The demo profile may seed other parent_links, so filter by
                // our own membership rather than asserting emptiness.
                .andExpect(jsonPath("$[?(@.displayName == 'Sora K.')]").doesNotExist());

        // --- 2. Create one child -------------------------------------------
        MvcResult create = mvc.perform(post("/api/v1/parent/children")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Sora K.","preferredLocale":"uz"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Sora K."))
                .andExpect(jsonPath("$.preferredLocale").value("uz"))
                .andExpect(jsonPath("$.userStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.membershipStatus").value("ACTIVE"))
                .andReturn();

        JsonNode body = mapper.readTree(create.getResponse().getContentAsString());
        UUID studentId = UUID.fromString(body.get("studentUserId").asText());

        // Membership must be STUDENT + ACTIVE in the parent's school
        Membership m = memberships.findByUserId(studentId).orElseThrow();
        assertThat(m.getRole()).isEqualTo(Role.STUDENT);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(m.getSchoolId()).isEqualTo(schoolId);

        // Parent link must exist
        List<ParentLink> links = parentLinks.findByIdParentUserId(parentId);
        assertThat(links).anyMatch(l -> l.getId().getStudentUserId().equals(studentId));

        // --- 3. List shows the new child -----------------------------------
        mvc.perform(get("/api/v1/parent/children").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Sora K.')]").exists());

        // --- 4. Admin sees the student in the member directory -------------
        String adminToken = login(ADMIN_EMAIL);
        mvc.perform(get("/api/v1/admin/members?role=STUDENT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Sora K.')]").exists());

        // --- 5. Unlink removes the parent_link but keeps the user intact ---
        mvc.perform(delete("/api/v1/parent/children/" + studentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(parentLinks.findByIdParentUserId(parentId))
                .noneMatch(l -> l.getId().getStudentUserId().equals(studentId));
        assertThat(users.findById(studentId)).isPresent();
        assertThat(memberships.findByUserId(studentId)).isPresent();
    }

    @Test
    void teacherCannotUseParentEndpoints() throws Exception {
        String teacherToken = login(TEACHER_EMAIL);

        mvc.perform(get("/api/v1/parent/children")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/parent/children")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Should not be created"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void createChildValidatesDisplayName() throws Exception {
        String token = login(PARENT_EMAIL);

        mvc.perform(post("/api/v1/parent/children")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":""}"""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------

    private UUID ensureUserWithMembership(String email, String displayName, Role role, UUID school) {
        User existing = users.findByEmail(email).orElse(null);
        if (existing != null) {
            // Make sure credentials are set, so logins in the test work.
            ensurePassword(existing.getId());
            return existing.getId();
        }

        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(displayName);
        u.setPreferredLocale("uz");
        u.setStatus(UserStatus.ACTIVE);
        users.save(u);

        ensurePassword(u.getId());

        Membership m = new Membership();
        m.setId(UUID.randomUUID());
        m.setUserId(u.getId());
        m.setSchoolId(school);
        m.setRole(role);
        m.setStatus(MembershipStatus.ACTIVE);
        memberships.save(m);

        return u.getId();
    }

    private void ensurePassword(UUID userId) {
        credentials.findByUserId(userId).ifPresentOrElse(c -> {
            c.setPasswordHash(passwords.hash(PASSWORD));
            c.setPasswordChangedAt(Instant.now());
            credentials.save(c);
        }, () -> {
            Credentials c = new Credentials();
            c.setUserId(userId);
            c.setPasswordHash(passwords.hash(PASSWORD));
            c.setPasswordChangedAt(Instant.now());
            credentials.save(c);
        });
    }

    private String login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
