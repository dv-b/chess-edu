package com.chessedu.platform.classes;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: create a class, add a teacher + student, verify detail + list,
 * remove a student, archive the class, and assert authz (teacher can't create).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassManagementIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "classes-admin@demo.local";
    static final String ADMIN_PASSWORD = "test-password-12345";
    static final String TEACHER_EMAIL = "classes-teacher@demo.local";
    static final String STUDENT_EMAIL = "classes-student@demo.local";

    private UUID teacherId;
    private UUID studentId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        ensureUser(ADMIN_EMAIL,   "Classes Admin",   Role.SCHOOL_ADMIN, school.getId(), true);
        teacherId = ensureUser(TEACHER_EMAIL, "Anna Tutor",      Role.TEACHER,      school.getId(), false);
        studentId = ensureUser(STUDENT_EMAIL, "Timur Student",   Role.STUDENT,      school.getId(), false);
    }

    @Test
    void fullClassLifecycle() throws Exception {
        String access = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        // 1. Create
        MvcResult created = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Monday Beginners","level":"BEGINNER","description":"Kids 8-10"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monday Beginners"))
                .andExpect(jsonPath("$.level").value("BEGINNER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.teachers.length()").value(0))
                .andExpect(jsonPath("$.students.length()").value(0))
                .andReturn();
        UUID classId = UUID.fromString(
                mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        // 2. Add teacher + student
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/teachers")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + teacherId + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/admin/classes/" + classId + "/students")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + studentId + "\"}"))
                .andExpect(status().isNoContent());

        // 3. Detail shows both
        mvc.perform(get("/api/v1/admin/classes/" + classId)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teachers[0].email").value(TEACHER_EMAIL))
                .andExpect(jsonPath("$.students[0].email").value(STUDENT_EMAIL));

        // 4. List summary has counts
        mvc.perform(get("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + classId + "')].teacherCount").value(1))
                .andExpect(jsonPath("$[?(@.id == '" + classId + "')].studentCount").value(1));

        // 5. Remove the student, verify gone
        mvc.perform(delete("/api/v1/admin/classes/" + classId + "/students/" + studentId)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());

        MvcResult afterRemove = mvc.perform(get("/api/v1/admin/classes/" + classId)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(afterRemove.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(0, body.get("students").size());

        // 6. Archive via PATCH
        mvc.perform(patch("/api/v1/admin/classes/" + classId)
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void rejectsNonMemberAsTeacher() throws Exception {
        String access = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        // Create a class
        MvcResult created = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Temp\"}"))
                .andExpect(status().isOk())
                .andReturn();
        UUID classId = UUID.fromString(
                mapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        // Trying to add the STUDENT as a TEACHER should fail
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/teachers")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + studentId + "\"}"))
                .andExpect(status().isForbidden());

        // And adding the TEACHER as a STUDENT should also fail
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/students")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + teacherId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCannotCreateClass() throws Exception {
        // Give the teacher a password, log them in, and confirm they can't POST.
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

        mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private UUID ensureUser(String email, String displayName, Role role,
                            UUID schoolId, boolean setPassword) {
        return users.findByEmail(email).map(User::getId).orElseGet(() -> {
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

            return u.getId();
        });
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
