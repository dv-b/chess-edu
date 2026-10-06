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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the student lesson surface end-to-end — counterpart to
 * {@link StudentParentHomeworkFlowIntegrationTest} with the lessons-
 * specific deltas called out per-case:
 *
 * <ul>
 *   <li>Student sees only the active lessons for the classes they're
 *       enrolled in; an archived lesson is filtered out.</li>
 *   <li>Student can mark READ (sets markedAt), NOT_STARTED (also
 *       stamps markedAt — the explicit state is preserved rather than
 *       collapsed into the null "implicit not started"), and reset to
 *       unmarked via a null status.</li>
 *   <li><b>Unlike</b> the homework endpoint, explicit
 *       {@code NOT_STARTED} on the lesson endpoint is <b>accepted</b>
 *       — it's the "opened-but-didn't-finish" trail documented on
 *       {@link com.chessedu.platform.classes.model.LessonCompletionStatus}.</li>
 *   <li>A student is 404'd when trying to mark a lesson in a class
 *       they don't belong to (we don't leak existence).</li>
 *   <li>A student is 404'd when trying to mark an archived lesson —
 *       same shape as the not-enrolled path.</li>
 *   <li>Detail endpoint returns block payloads <b>unscrubbed</b>: an
 *       MCQ's {@code correctIndex} and a BOARD_WALKTHROUGH's full
 *       move list ship on the wire by design. The client owns
 *       reveal timing; lessons are study material, not gradable
 *       homework.</li>
 *   <li>A non-student role (school admin) on {@code /me/lessons} is
 *       403 at the pre-authorize layer.</li>
 * </ul>
 *
 * <p><b>Isolation:</b> student users are created fresh <em>per test</em>
 * (via {@link #freshStudent(String)}) rather than shared from
 * {@code @BeforeEach}. Each student's enrolments are private to their
 * test, so list-length assertions don't leak state between tests
 * running in the same context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentLessonFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "sl-admin@demo.local";
    static final String ADMIN_PW    = "sl-admin-12345";

    /** Standard start-of-game FEN — reused across board-walkthrough assertions. */
    private static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    UUID schoolId;
    UUID adminId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        schoolId = school.getId();
        adminId  = ensureUser(ADMIN_EMAIL, "SL Admin", Role.SCHOOL_ADMIN, schoolId, ADMIN_PW);
    }

    // ------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------

    @Test
    void studentSeesOwnActiveLessonsOnly() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("see-own");
        Fresh other = freshStudent("see-own-other");

        UUID classA = createClass(admin, "SL Class A " + fs.tag);
        UUID classB = createClass(admin, "SL Class B " + fs.tag); // my student not on this one
        addStudent(admin, classA, fs.id);
        addStudent(admin, classB, other.id);

        UUID lessonA1 = createLesson(admin, classA, "Active A1");
        UUID lessonA2 = createLesson(admin, classA, "Active A2");
        createLesson(admin, classB, "B-lesson-not-mine");

        // Archive one of the student's lessons — it should drop out.
        mvc.perform(delete(lessonsUrl(classA) + "/" + lessonA2)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        String student = login(fs.email, fs.password);
        mvc.perform(get("/api/v1/me/lessons")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(lessonA1.toString()))
                .andExpect(jsonPath("$[0].className").value("SL Class A " + fs.tag))
                // Default / unmarked row: status is null so Jackson strips it.
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].blockCount").value(0));
    }

    @Test
    void studentCanMarkReadAndReset() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("mark");
        UUID classId = createClass(admin, "SL Class Mark " + fs.tag);
        addStudent(admin, classId, fs.id);
        UUID lessonId = createLesson(admin, classId, "Markable");

        String student = login(fs.email, fs.password);

        // READ stamps markedAt.
        mvc.perform(put("/api/v1/me/lessons/" + lessonId + "/completion")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READ"))
                .andExpect(jsonPath("$.markedAt").exists());

        // null status clears — row deleted, admin tally drops back to 0.
        mvc.perform(put("/api/v1/me/lessons/" + lessonId + "/completion")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.markedAt").doesNotExist());

        // Admin sees the cleared state — readCount is 0 on the list.
        mvc.perform(get(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].readCount").value(0));
    }

    /**
     * Lessons diverge from homework here: explicit {@code NOT_STARTED} is
     * a valid status write (preserves the "opened-but-didn't-finish"
     * trail that null would erase). Contrast with
     * {@link StudentParentHomeworkFlowIntegrationTest#studentCannotMarkNotStartedExplicitly}.
     */
    @Test
    void studentCanMarkNotStartedExplicitly() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("ns");
        UUID classId = createClass(admin, "SL Class NS " + fs.tag);
        addStudent(admin, classId, fs.id);
        UUID lessonId = createLesson(admin, classId, "Opened but not finished");

        String student = login(fs.email, fs.password);
        mvc.perform(put("/api/v1/me/lessons/" + lessonId + "/completion")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_STARTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                // NOT_STARTED still refreshes markedAt so the admin sheet
                // can show "last touched at" consistently.
                .andExpect(jsonPath("$.markedAt").exists());
    }

    @Test
    void studentCannotMarkLessonForClassTheyreNotIn() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("outside");
        Fresh other = freshStudent("outside-other");

        UUID classId = createClass(admin, "SL Class Outside " + fs.tag);
        addStudent(admin, classId, other.id); // fs NOT on this roster
        UUID lessonId = createLesson(admin, classId, "Someone else's");

        String student = login(fs.email, fs.password);
        mvc.perform(put("/api/v1/me/lessons/" + lessonId + "/completion")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\"}"))
                .andExpect(status().isNotFound());

        // Detail is gated the same way.
        mvc.perform(get("/api/v1/me/lessons/" + lessonId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentCannotMarkArchivedLesson() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("archived");

        UUID classId = createClass(admin, "SL Class Archived " + fs.tag);
        addStudent(admin, classId, fs.id);
        UUID lessonId = createLesson(admin, classId, "Soon archived");

        // Admin archives the lesson.
        mvc.perform(delete(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        String student = login(fs.email, fs.password);
        // Student attempts to mark it as read — same 404 shape as
        // a lesson they have no access to.
        mvc.perform(put("/api/v1/me/lessons/" + lessonId + "/completion")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READ\"}"))
                .andExpect(status().isNotFound());

        // Detail also 404s.
        mvc.perform(get("/api/v1/me/lessons/" + lessonId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isNotFound());
    }

    /**
     * Lessons-specific contract: block payloads are <b>not</b> scrubbed
     * on the wire. An MCQ's {@code correctIndex} ships to the student
     * verbatim; a BOARD_WALKTHROUGH's full move list is available too.
     * The client mediates reveal UX; the server treats lessons as
     * study material rather than gradable homework.
     */
    @Test
    void studentDetailReturnsUnscrubedResources() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("detail");
        UUID classId = createClass(admin, "SL Class Detail " + fs.tag);
        addStudent(admin, classId, fs.id);

        // Create a lesson with a BOARD_WALKTHROUGH + MCQ. The MCQ carries
        // a correctIndex that, in the homework world, would be stripped.
        String body = """
                {
                  "title": "Revisitable study material",
                  "resources": [
                    {
                      "kind": "BOARD_WALKTHROUGH",
                      "payload": {
                        "startingFen": "%s",
                        "moves": ["e2e4", "e7e5"],
                        "orientation": "white"
                      }
                    },
                    {
                      "kind": "MCQ",
                      "payload": {
                        "question": "Best central pawn push on move 1?",
                        "options": ["1.e4", "1.a4", "1.h4", "1.Nh3"],
                        "correctIndex": 0,
                        "explanation": "Controls d5 and f5 and opens diagonals for bishop + queen."
                      }
                    }
                  ]
                }
                """.formatted(START_FEN);

        MvcResult created = mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        UUID lessonId = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        String student = login(fs.email, fs.password);
        mvc.perform(get("/api/v1/me/lessons/" + lessonId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Revisitable study material"))
                .andExpect(jsonPath("$.className").value("SL Class Detail " + fs.tag))
                .andExpect(jsonPath("$.resources.length()").value(2))
                // BOARD_WALKTHROUGH: full move list preserved on the wire.
                .andExpect(jsonPath("$.resources[0].kind").value("BOARD_WALKTHROUGH"))
                .andExpect(jsonPath("$.resources[0].payload.moves.length()").value(2))
                .andExpect(jsonPath("$.resources[0].payload.moves[0]").value("e2e4"))
                // MCQ: correctIndex + explanation are NOT stripped.
                .andExpect(jsonPath("$.resources[1].kind").value("MCQ"))
                .andExpect(jsonPath("$.resources[1].payload.correctIndex").value(0))
                .andExpect(jsonPath("$.resources[1].payload.explanation")
                        .value("Controls d5 and f5 and opens diagonals for bishop + queen."))
                // Not yet marked.
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.markedAt").doesNotExist());
    }

    @Test
    void studentListIsEmptyWhenUnenrolled() throws Exception {
        Fresh fs = freshStudent("empty");
        String student = login(fs.email, fs.password);
        mvc.perform(get("/api/v1/me/lessons")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nonStudentCannotUseMeLessons() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        mvc.perform(get("/api/v1/me/lessons")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A per-test user wrapper — unique email + password per instance so tests
     *  can create as many fresh users as they need without clashing. */
    private record Fresh(UUID id, String email, String password, String tag) {}

    private Fresh freshStudent(String tag) {
        String suffix = shortId();
        String email = "sl-stu-" + tag + "-" + suffix + "@demo.local";
        String pw = "sl-stu-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "SL Stu " + tag, Role.STUDENT, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String lessonsUrl(UUID classId) {
        return "/api/v1/admin/classes/" + classId + "/lessons";
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

    private UUID createLesson(String access, UUID classId, String title) throws Exception {
        MvcResult res = mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
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

    private UUID ensureUser(String email, String displayName, Role role, UUID schoolIdVal, String password) {
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
        return mapper.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
