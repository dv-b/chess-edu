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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the block-attempt slice end-to-end (Task #28):
 *
 * <ul>
 *   <li>Happy path: student submits moves on a block, teacher reviews as
 *       "needs work", student unlocks the solution, submits again, teacher
 *       reviews the fresh attempt as "OK". Verifies status transitions,
 *       history preservation via {@code replacedById}, and that the student
 *       detail view un-strips the solution only for blocks with a revealed
 *       attempt.</li>
 *   <li>Rejections: empty / illegal moves, reveal before review, reveal on
 *       a block with no authored solution, reviewing a superseded attempt,
 *       non-enrolled student submitting, unassigned teacher reviewing.</li>
 * </ul>
 *
 * <p>Mirrors the structure of {@link StudentParentHomeworkFlowIntegrationTest}
 * — fresh per-test students/teachers so list-length assertions don't bleed
 * between runs in the same Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomeworkBlockAttemptFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "ba-admin@demo.local";
    static final String ADMIN_PW    = "ba-admin-12345";

    static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    UUID schoolId;
    UUID adminId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        schoolId = school.getId();
        adminId  = ensureUser(ADMIN_EMAIL, "BA Admin", Role.SCHOOL_ADMIN, schoolId, ADMIN_PW);
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void submitReviewRevealOverwriteAndApproveFlow() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("happy");
        UUID classId = createClass(admin, "BA Class " + stu.tag);
        addStudent(admin, classId, stu.id);
        // Homework with one BOARD block that carries an authored solution.
        UUID hwId = createHwWithBoard(admin, classId, "Knight walk", "g1f3");

        String student = login(stu.email, stu.password);

        // --- 1. Student submits an "attempt" that the teacher will flag. ---
        MvcResult sub1 = mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 1.a3 is legal but not the author's solution.
                        .content("{\"moves\":[\"a2a3\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.blockIndex").value(0))
                .andExpect(jsonPath("$.moves[0]").value("a2a3"))
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.solutionRevealedAt").doesNotExist())
                .andReturn();
        UUID attempt1 = UUID.fromString(mapper.readTree(sub1.getResponse().getContentAsString())
                .get("id").asText());

        // --- 2. Student detail should now surface the live attempt, with the
        //        solution still stripped (not revealed yet). ---
        mvc.perform(get("/api/v1/me/homework/" + hwId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.resources[0].payload.solution").doesNotExist());

        // --- 3. Teacher reviews as NEEDS_WORK with a note. ---
        mvc.perform(post(reviewUrl(classId, hwId, attempt1))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REVIEWED_NEEDS_WORK",
                                 "teacherNote":"Consider developing a piece"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED_NEEDS_WORK"))
                .andExpect(jsonPath("$.teacherNote").value("Consider developing a piece"))
                .andExpect(jsonPath("$.reviewedAt").exists());

        // --- 4. Student reveals the solution. Status stays NEEDS_WORK (the
        //        teacher's assessment is preserved); solutionRevealedAt gets
        //        stamped. ---
        mvc.perform(post(revealUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED_NEEDS_WORK"))
                .andExpect(jsonPath("$.solutionRevealedAt").exists());

        // Calling reveal again is idempotent.
        mvc.perform(post(revealUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solutionRevealedAt").exists());

        // --- 5. Student detail un-strips the solution for this block now
        //        that the attempt is revealed. ---
        mvc.perform(get("/api/v1/me/homework/" + hwId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].payload.solution.uci[0]").value("g1f3"));

        // --- 6. Student overwrites the attempt with a fresh submission. The
        //        old row is marked replacedById; the new one is live. ---
        MvcResult sub2 = mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[\"g1f3\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.moves[0]").value("g1f3"))
                // Fresh attempt carries no prior review state.
                .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                .andReturn();
        UUID attempt2 = UUID.fromString(mapper.readTree(sub2.getResponse().getContentAsString())
                .get("id").asText());

        // History endpoint returns both rows, oldest first, with the first
        // one carrying a replacedById pointing at the second.
        mvc.perform(get(historyUrl(classId, hwId, stu.id, 0))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(attempt1.toString()))
                .andExpect(jsonPath("$[0].replacedById").value(attempt2.toString()))
                .andExpect(jsonPath("$[1].id").value(attempt2.toString()))
                .andExpect(jsonPath("$[1].replacedById").doesNotExist());

        // --- 7. Reviewing the REPLACED attempt fails with 409. ---
        mvc.perform(post(reviewUrl(classId, hwId, attempt1))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED_OK\"}"))
                .andExpect(status().isConflict());

        // --- 8. Teacher approves the live attempt. ---
        mvc.perform(post(reviewUrl(classId, hwId, attempt2))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED_OK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED_OK"));

        // Admin detail exposes exactly one live attempt across all students.
        mvc.perform(get(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].id").value(attempt2.toString()))
                .andExpect(jsonPath("$.attempts[0].status").value("REVIEWED_OK"));
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    void rejectsEmptyMoves() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("empty");
        UUID classId = createClass(admin, "BA Class " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "bare", null);

        String student = login(stu.email, stu.password);
        mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsIllegalMove() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("illegal");
        UUID classId = createClass(admin, "BA Class " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "bare", null);

        String student = login(stu.email, stu.password);
        // e2e5 is not a legal first move for White.
        mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[\"e2e5\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRevealBeforeReview() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("rev-before");
        UUID classId = createClass(admin, "BA Class " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "bare", "g1f3");

        String student = login(stu.email, stu.password);
        mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[\"a2a3\"]}"))
                .andExpect(status().isOk());

        mvc.perform(post(revealUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRevealWhenNoSolutionAuthored() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("no-sol");
        UUID classId = createClass(admin, "BA Class " + stu.tag);
        addStudent(admin, classId, stu.id);
        // null solution — author hasn't recorded one.
        UUID hwId = createHwWithBoard(admin, classId, "no-sol", null);

        String student = login(stu.email, stu.password);
        mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[\"a2a3\"]}"))
                .andExpect(status().isOk());

        // Even after a NEEDS_WORK review, reveal fails because the block
        // carries no solution.
        MvcResult det = mvc.perform(get(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        UUID attemptId = UUID.fromString(mapper.readTree(det.getResponse().getContentAsString())
                .get("attempts").get(0).get("id").asText());

        mvc.perform(post(reviewUrl(classId, hwId, attemptId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED_NEEDS_WORK\"}"))
                .andExpect(status().isOk());

        mvc.perform(post(revealUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonEnrolledStudentSubmit() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh enrolled = freshStudent("in");
        Fresh outsider = freshStudent("out");
        UUID classId = createClass(admin, "BA Class " + enrolled.tag);
        addStudent(admin, classId, enrolled.id);
        UUID hwId = createHwWithBoard(admin, classId, "secret", null);

        // Outsider is in the same school but not on the class roster.
        String out = login(outsider.email, outsider.password);
        // 404 on purpose — don't leak the homework's existence.
        mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + out)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[\"a2a3\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unassignedTeacherCannotReview() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("assign");
        Fresh teachIn = freshTeacher("in");
        Fresh teachOut = freshTeacher("out");
        UUID classId = createClass(admin, "BA Class " + stu.tag);
        addStudent(admin, classId, stu.id);
        addTeacher(admin, classId, teachIn.id);
        UUID hwId = createHwWithBoard(admin, classId, "t-check", null);

        String student = login(stu.email, stu.password);
        MvcResult sub = mvc.perform(post(attemptsUrl(hwId, 0))
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[\"a2a3\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        UUID attemptId = UUID.fromString(mapper.readTree(sub.getResponse().getContentAsString())
                .get("id").asText());

        // Assigned teacher can review.
        String inTok = login(teachIn.email, teachIn.password);
        mvc.perform(post(reviewUrl(classId, hwId, attemptId))
                        .header("Authorization", "Bearer " + inTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED_OK\"}"))
                .andExpect(status().isOk());

        // Unassigned teacher in the same school — 403.
        String outTok = login(teachOut.email, teachOut.password);
        mvc.perform(post(reviewUrl(classId, hwId, attemptId))
                        .header("Authorization", "Bearer " + outTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REVIEWED_OK\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Fresh(UUID id, String email, String password, String tag) {}

    private Fresh freshStudent(String tag) {
        String suffix = shortId();
        String email = "ba-stu-" + tag + "-" + suffix + "@demo.local";
        String pw = "ba-stu-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "BA Stu " + tag, Role.STUDENT, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private Fresh freshTeacher(String tag) {
        String suffix = shortId();
        String email = "ba-teach-" + tag + "-" + suffix + "@demo.local";
        String pw = "ba-teach-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "BA Teach " + tag, Role.TEACHER, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String hwUrl(UUID classId) {
        return "/api/v1/admin/classes/" + classId + "/homework";
    }

    private String attemptsUrl(UUID hwId, int blockIndex) {
        return "/api/v1/me/homework/" + hwId + "/blocks/" + blockIndex + "/attempts";
    }

    private String revealUrl(UUID hwId, int blockIndex) {
        return "/api/v1/me/homework/" + hwId + "/blocks/" + blockIndex + "/reveal-solution";
    }

    private String reviewUrl(UUID classId, UUID hwId, UUID attemptId) {
        return hwUrl(classId) + "/" + hwId + "/attempts/" + attemptId + "/review";
    }

    private String historyUrl(UUID classId, UUID hwId, UUID studentId, int blockIndex) {
        return hwUrl(classId) + "/" + hwId + "/students/" + studentId
                + "/blocks/" + blockIndex + "/attempts";
    }

    private UUID createClass(String access, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/admin/classes")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(mapper.readTree(res.getResponse().getContentAsString())
                .get("id").asText());
    }

    /**
     * Create a homework carrying a single BOARD block. If
     * {@code solutionFirstMove} is non-null, the block includes a one-move
     * authored solution (which the student can then reveal).
     */
    private UUID createHwWithBoard(String access, UUID classId, String title,
                                   String solutionFirstMove) throws Exception {
        String solutionFragment = solutionFirstMove == null
                ? ""
                : ",\"solution\":{\"uci\":[\"" + solutionFirstMove + "\"]}";
        String body = """
                {"title":"%s",
                 "resources":[{
                   "kind":"BOARD",
                   "payload":{
                     "startingFen":"%s"%s
                   }
                 }]}
                """.formatted(title, START_FEN, solutionFragment);
        MvcResult res = mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(mapper.readTree(res.getResponse().getContentAsString())
                .get("id").asText());
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

    private UUID ensureUser(String email, String displayName, Role role,
                            UUID schoolIdVal, String password) {
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
            m.setSchoolId(schoolIdVal);
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
        return mapper.readTree(r.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
