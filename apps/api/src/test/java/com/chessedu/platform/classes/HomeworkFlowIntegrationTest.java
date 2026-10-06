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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the homework admin slice end-to-end:
 *
 * <ul>
 *   <li>Admin creates a homework against a class, list/detail return it
 *       with tally=0/0 and per-student NOT_STARTED placeholders.</li>
 *   <li>Admin overrides student status: DONE flips the tally and sets
 *       submittedAt; clearing drops the row back to unmarked.</li>
 *   <li>PATCH updates title + clearSession flag detaches the session.</li>
 *   <li>Archive hides the row from the default list and refuses further
 *       edits with 409.</li>
 *   <li>Assigned teacher can manage; unassigned teacher in same school is
 *       forbidden.</li>
 *   <li>Creating with a {@code sessionId} from a different class is 400.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomeworkFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "hw-admin@demo.local";
    static final String ADMIN_PW    = "hw-pass-12345";
    static final String TEACHER_A   = "hw-teach-a@demo.local";
    static final String TEACHER_A_PW = "hw-t1-12345";
    static final String TEACHER_B   = "hw-teach-b@demo.local";
    static final String TEACHER_B_PW = "hw-t2-12345";
    static final String STUDENT_1   = "hw-stu-1@demo.local";
    static final String STUDENT_2   = "hw-stu-2@demo.local";

    UUID adminId;
    UUID teacherAId;
    UUID teacherBId;
    UUID student1Id;
    UUID student2Id;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        adminId    = ensureUser(ADMIN_EMAIL, "HW Admin",     Role.SCHOOL_ADMIN, school.getId(), ADMIN_PW);
        teacherAId = ensureUser(TEACHER_A,   "HW Teacher A", Role.TEACHER,      school.getId(), TEACHER_A_PW);
        teacherBId = ensureUser(TEACHER_B,   "HW Teacher B", Role.TEACHER,      school.getId(), TEACHER_B_PW);
        student1Id = ensureUser(STUDENT_1,   "HW Student 1", Role.STUDENT,      school.getId(), null);
        student2Id = ensureUser(STUDENT_2,   "HW Student 2", Role.STUDENT,      school.getId(), null);
    }

    @Test
    void adminCreatesListsUpdatesAndOverridesSubmission() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Class A");
        UUID sessionId = createAdHocSession(admin, classId, 2);
        addStudent(admin, classId, student1Id);
        addStudent(admin, classId, student2Id);

        // Create homework tied to the session.
        MvcResult created = mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Tactics set #1",
                                 "instructions":"Solve puzzles 1 through 10",
                                 "sessionId":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tactics set #1"))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.submissions.length()").value(2))
                .andExpect(jsonPath("$.submissions[0].status").doesNotExist())
                .andReturn();
        UUID hwId = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        // List: one active row, tally 0/0.
        mvc.perform(get(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rosterSize").value(2))
                .andExpect(jsonPath("$[0].doneCount").value(0))
                .andExpect(jsonPath("$[0].skippedCount").value(0));

        // Teacher / admin override: mark student 1 DONE.
        mvc.perform(put(hwUrl(classId) + "/" + hwId + "/submissions/" + student1Id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions[?(@.studentUserId == '%s')].status".formatted(student1Id))
                        .value("DONE"))
                .andExpect(jsonPath("$.submissions[?(@.studentUserId == '%s')].submittedAt".formatted(student1Id))
                        .exists());

        // List now shows 1/0.
        mvc.perform(get(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[0].doneCount").value(1))
                .andExpect(jsonPath("$[0].skippedCount").value(0));

        // PATCH title + clearSession detaches session.
        mvc.perform(patch(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tactics set #1 (v2)\",\"clearSession\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tactics set #1 (v2)"))
                .andExpect(jsonPath("$.sessionId").doesNotExist());

        // Clearing a student's submission (status=null) drops the row.
        mvc.perform(put(hwUrl(classId) + "/" + hwId + "/submissions/" + student1Id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions[?(@.studentUserId == '%s')].status".formatted(student1Id))
                        .doesNotExist());
    }

    @Test
    void archiveHidesFromListAndBlocksEdits() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Class B");
        addStudent(admin, classId, student1Id);
        UUID hwId = createHw(admin, classId, "Archivable");

        mvc.perform(delete(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        // Default list excludes archived rows.
        mvc.perform(get(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // includeArchived=true brings it back.
        mvc.perform(get(hwUrl(classId) + "?includeArchived=true")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].archivedAt").exists());

        // Further edits are 409.
        mvc.perform(patch(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"no-op\"}"))
                .andExpect(status().isConflict());

        mvc.perform(put(hwUrl(classId) + "/" + hwId + "/submissions/" + student1Id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void assignedTeacherCanManageUnassignedCannot() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Class C");
        addStudent(admin, classId, student1Id);
        addTeacher(admin, classId, teacherAId);

        String aTok = login(TEACHER_A, TEACHER_A_PW);
        String bTok = login(TEACHER_B, TEACHER_B_PW);

        // Teacher A (assigned) can create.
        MvcResult r = mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + aTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"teacher-A-hw\"}"))
                .andExpect(status().isOk())
                .andReturn();
        UUID hwId = UUID.fromString(mapper.readTree(r.getResponse().getContentAsString())
                .get("id").asText());

        // Teacher A can override.
        mvc.perform(put(hwUrl(classId) + "/" + hwId + "/submissions/" + student1Id)
                        .header("Authorization", "Bearer " + aTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SKIPPED\"}"))
                .andExpect(status().isOk());

        // Teacher B (same school, not assigned) gets 403 on list and write.
        mvc.perform(get(hwUrl(classId))
                        .header("Authorization", "Bearer " + bTok))
                .andExpect(status().isForbidden());
        mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + bTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Chess content (homework_resource) coverage
    // ------------------------------------------------------------------

    /** Standard start-of-game FEN, used as a harmless seed for board blocks. */
    private static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void createsHomeworkWithBoardResourcesAndReturnsThemInOrder() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Chess A");
        addStudent(admin, classId, student1Id);

        // Two blocks: a plain position, then a position with a few moves +
        // an explicit orientation + a hidden solution.
        String body = """
                {
                  "title": "King's pawn basics",
                  "resources": [
                    {
                      "kind": "BOARD",
                      "title": "Starting position",
                      "payload": {
                        "startingFen": "%s"
                      }
                    },
                    {
                      "kind": "BOARD",
                      "payload": {
                        "startingFen": "%s",
                        "moves": ["e2e4", "e7e5"],
                        "orientation": "BLACK",
                        "solution": { "uci": ["g1f3"] }
                      }
                    }
                  ]
                }
                """.formatted(START_FEN, START_FEN);

        MvcResult res = mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(2))
                .andExpect(jsonPath("$.resources[0].position").value(0))
                .andExpect(jsonPath("$.resources[0].kind").value("BOARD"))
                .andExpect(jsonPath("$.resources[0].title").value("Starting position"))
                .andExpect(jsonPath("$.resources[0].payload.startingFen").value(START_FEN))
                // Default orientation when none was sent.
                .andExpect(jsonPath("$.resources[0].payload.orientation").value("white"))
                .andExpect(jsonPath("$.resources[1].position").value(1))
                // Orientation normalised to lowercase.
                .andExpect(jsonPath("$.resources[1].payload.orientation").value("black"))
                .andExpect(jsonPath("$.resources[1].payload.moves.length()").value(2))
                .andExpect(jsonPath("$.resources[1].payload.moves[0]").value("e2e4"))
                .andExpect(jsonPath("$.resources[1].payload.solution.uci[0]").value("g1f3"))
                .andReturn();
        UUID hwId = UUID.fromString(mapper.readTree(res.getResponse().getContentAsString())
                .get("id").asText());

        // GET detail returns the same ordered list.
        mvc.perform(get(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(2))
                .andExpect(jsonPath("$.resources[0].position").value(0))
                .andExpect(jsonPath("$.resources[1].position").value(1));
    }

    @Test
    void updateReplacesResourceListAndEmptyListClearsIt() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Chess B");
        UUID hwId = createHw(admin, classId, "Plain hw");

        // PATCH with a new resources list replaces the (empty) stored list.
        mvc.perform(patch(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resources": [
                                  {"kind":"BOARD","payload":{"startingFen":"%s"}}
                                ]}
                                """.formatted(START_FEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.resources[0].position").value(0));

        // PATCH with an empty list clears everything.
        mvc.perform(patch(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resources\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(0));

        // PATCH without the field at all leaves resources alone — re-add one,
        // then patch an unrelated field and confirm the list survives.
        mvc.perform(patch(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resources": [
                                  {"kind":"BOARD","payload":{"startingFen":"%s"}}
                                ]}
                                """.formatted(START_FEN)))
                .andExpect(status().isOk());

        mvc.perform(patch(hwUrl(classId) + "/" + hwId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Still here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1));
    }

    @Test
    void rejectsIllegalFen() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Chess C");

        mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad fen",
                                 "resources": [
                                   {"kind":"BOARD","payload":{"startingFen":"not-a-fen"}}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsIllegalMoveInSequence() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Chess D");

        // e2e5 is not a legal move from the starting position.
        mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad move",
                                 "resources": [
                                   {"kind":"BOARD","payload":{
                                      "startingFen":"%s",
                                      "moves":["e2e5"]}}
                                 ]}
                                """.formatted(START_FEN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownKind() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Chess E");

        mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"unknown kind",
                                 "resources": [
                                   {"kind":"QUIZ","payload":{"question":"?"}}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsIllegalSolutionMove() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "HW Chess F");

        // After 1.e4 e5, it's white to move. "e7e5" (a black move) is
        // illegal from that position.
        mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad solution",
                                 "resources": [
                                   {"kind":"BOARD","payload":{
                                      "startingFen":"%s",
                                      "moves":["e2e4","e7e5"],
                                      "solution":{"uci":["e7e5"]}}}
                                 ]}
                                """.formatted(START_FEN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSessionFromDifferentClass() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classA = createClass(admin, "HW Class D");
        UUID classB = createClass(admin, "HW Class E");
        UUID sessionInB = createAdHocSession(admin, classB, 3);

        mvc.perform(post(hwUrl(classA))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"cross-class\",\"sessionId\":\"%s\"}"
                                .formatted(sessionInB)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String hwUrl(UUID classId) {
        return "/api/v1/admin/classes/" + classId + "/homework";
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

    private UUID createHw(String access, UUID classId, String title) throws Exception {
        MvcResult res = mvc.perform(post(hwUrl(classId))
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
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
