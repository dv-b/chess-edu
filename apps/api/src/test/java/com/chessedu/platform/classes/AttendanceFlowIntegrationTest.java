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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the attendance slice end-to-end:
 *
 * <ul>
 *   <li>Admin creates a class + ad-hoc session; roster fetch returns the
 *       student unmarked; admin bulk-marks; re-read returns the same.</li>
 *   <li>Assigned teacher can both read and write; clearing a mark removes the row.</li>
 *   <li>Cancelled session refuses writes (409) but still accepts reads.</li>
 *   <li>Unassigned teacher in the same school is forbidden.</li>
 *   <li>Bulk write rejects a student who isn't on the class roster.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "att-admin@demo.local";
    static final String ADMIN_PW    = "att-pass-12345";
    static final String TEACHER_A   = "att-teach-a@demo.local";
    static final String TEACHER_A_PW = "att-t1-12345";
    static final String TEACHER_B   = "att-teach-b@demo.local";
    static final String TEACHER_B_PW = "att-t2-12345";
    static final String STUDENT_1   = "att-stu-1@demo.local";
    static final String STUDENT_2   = "att-stu-2@demo.local";
    static final String OUTSIDER    = "att-outsider@demo.local";

    UUID adminId;
    UUID teacherAId;
    UUID teacherBId;
    UUID student1Id;
    UUID student2Id;
    UUID outsiderId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        adminId    = ensureUser(ADMIN_EMAIL, "Att Admin",    Role.SCHOOL_ADMIN, school.getId(), ADMIN_PW);
        teacherAId = ensureUser(TEACHER_A,   "Teacher Alpha", Role.TEACHER,     school.getId(), TEACHER_A_PW);
        teacherBId = ensureUser(TEACHER_B,   "Teacher Bravo", Role.TEACHER,     school.getId(), TEACHER_B_PW);
        student1Id = ensureUser(STUDENT_1,   "Student One",   Role.STUDENT,     school.getId(), null);
        student2Id = ensureUser(STUDENT_2,   "Student Two",   Role.STUDENT,     school.getId(), null);
        outsiderId = ensureUser(OUTSIDER,    "Outsider Stu",  Role.STUDENT,     school.getId(), null);
    }

    @Test
    void adminMarksAndRereadsTheSheet() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "Att Class A");
        UUID sessionId = createAdHocSession(admin, classId, 1);

        // Attach students 1 and 2 (NOT the outsider).
        addStudent(admin, classId, student1Id);
        addStudent(admin, classId, student2Id);

        // Initial read: both students unmarked.
        mvc.perform(get(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.sessionStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.marks.length()").value(2))
                .andExpect(jsonPath("$.marks[0].status").doesNotExist())
                .andExpect(jsonPath("$.marks[1].status").doesNotExist());

        // Bulk mark: s1 PRESENT, s2 LATE with a note.
        mvc.perform(put(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[
                                  {"studentUserId":"%s","status":"PRESENT"},
                                  {"studentUserId":"%s","status":"LATE","note":"15min late"}
                                ]}
                                """.formatted(student1Id, student2Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marks.length()").value(2))
                .andExpect(jsonPath("$.marks[?(@.studentUserId == '%s')].status".formatted(student1Id))
                        .value("PRESENT"))
                .andExpect(jsonPath("$.marks[?(@.studentUserId == '%s')].status".formatted(student2Id))
                        .value("LATE"))
                .andExpect(jsonPath("$.marks[?(@.studentUserId == '%s')].note".formatted(student2Id))
                        .value("15min late"));

        // Clear student 2's mark (status = null).
        mvc.perform(put(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[
                                  {"studentUserId":"%s","status":null}
                                ]}
                                """.formatted(student2Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marks[?(@.studentUserId == '%s')].status".formatted(student1Id))
                        .value("PRESENT"))
                .andExpect(jsonPath("$.marks[?(@.studentUserId == '%s')].status".formatted(student2Id))
                        .doesNotExist());
    }

    @Test
    void assignedTeacherCanMarkUnassignedTeacherCannot() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "Att Class B");
        UUID sessionId = createAdHocSession(admin, classId, 2);
        addStudent(admin, classId, student1Id);
        addTeacher(admin, classId, teacherAId);

        String aTok = login(TEACHER_A, TEACHER_A_PW);
        String bTok = login(TEACHER_B, TEACHER_B_PW);

        // Teacher A (assigned): read + write both ok.
        mvc.perform(get(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + aTok))
                .andExpect(status().isOk());
        mvc.perform(put(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + aTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"studentUserId":"%s","status":"ABSENT"}]}
                                """.formatted(student1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marks[?(@.studentUserId == '%s')].status".formatted(student1Id))
                        .value("ABSENT"));

        // Teacher B (same school, not assigned): forbidden on both.
        mvc.perform(get(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + bTok))
                .andExpect(status().isForbidden());
        mvc.perform(put(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + bTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"studentUserId":"%s","status":"PRESENT"}]}
                                """.formatted(student1Id)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelledSessionRefusesWrites() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "Att Class C");
        UUID sessionId = createAdHocSession(admin, classId, 3);
        addStudent(admin, classId, student1Id);

        mvc.perform(patch("/api/v1/admin/classes/" + classId + "/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        // Reads still work.
        mvc.perform(get(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("CANCELLED"));

        // Writes are refused (409).
        mvc.perform(put(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"studentUserId":"%s","status":"PRESENT"}]}
                                """.formatted(student1Id)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsStudentNotOnRoster() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "Att Class D");
        UUID sessionId = createAdHocSession(admin, classId, 4);
        addStudent(admin, classId, student1Id);

        // Outsider was never added to the class.
        mvc.perform(put(attUrl(classId, sessionId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"studentUserId":"%s","status":"PRESENT"}]}
                                """.formatted(outsiderId)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String attUrl(UUID classId, UUID sessionId) {
        return "/api/v1/admin/classes/" + classId + "/sessions/" + sessionId + "/attendance";
    }

    private UUID createClass(String access, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(mapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createAdHocSession(String access, UUID classId, int daysAhead) throws Exception {
        Instant start = Instant.now().plus(Duration.ofDays(daysAhead));
        Instant end   = start.plus(Duration.ofMinutes(60));
        MvcResult res = mvc.perform(post("/api/v1/admin/classes/" + classId + "/sessions")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"%s","endsAt":"%s"}
                                """.formatted(start, end)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(mapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private void addStudent(String access, UUID classId, UUID userId) throws Exception {
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/students")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\"}"))
                .andExpect(status().isNoContent());
    }

    private void addTeacher(String access, UUID classId, UUID userId) throws Exception {
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/teachers")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\"}"))
                .andExpect(status().isNoContent());
    }

    private UUID ensureUser(String email, String displayName, Role role, UUID schoolId, String password) {
        return users.findByEmail(email).map(User::getId).orElseGet(() -> {
            User u = new User();
            u.setId(UUID.randomUUID());
            u.setEmail(email);
            u.setDisplayName(displayName);
            u.setPreferredLocale("uz");
            u.setStatus(UserStatus.ACTIVE);
            users.save(u);

            if (password != null) {
                Credentials c = new Credentials();
                c.setUserId(u.getId());
                c.setPasswordHash(passwords.hash(password));
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
        MvcResult r = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

}
