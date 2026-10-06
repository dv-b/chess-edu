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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the admin lesson slice end-to-end. Mirrors
 * {@link HomeworkFlowIntegrationTest} in shape, plus per-kind validator
 * coverage (the only place the four lesson block validators see actual
 * integration traffic in v1).
 *
 * <ul>
 *   <li>Admin creates a lesson with all four block kinds; list / detail
 *       return them in order with canonical casing + derived video
 *       provider.</li>
 *   <li>Three-state PATCH on {@code resources}: populated replaces,
 *       empty clears, missing leaves alone.</li>
 *   <li>PATCH {@code clearSession} detaches the anchor.</li>
 *   <li>Archive hides the lesson from the default list and blocks
 *       further edits with 409.</li>
 *   <li>Assigned teacher can manage; unassigned teacher in the same
 *       school is forbidden.</li>
 *   <li>Creating with a {@code sessionId} from a different class is
 *       400.</li>
 *   <li>Validator rejection paths: PROSE empty, BOARD_WALKTHROUGH
 *       illegal FEN / illegal prefix move / annotation ply out of
 *       range, MCQ wrong option count / duplicate options / bad
 *       correctIndex, VIDEO http-not-https / non-allowlisted host, and
 *       an unknown kind.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LessonFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "le-admin@demo.local";
    static final String ADMIN_PW    = "le-pass-12345";
    static final String TEACHER_A   = "le-teach-a@demo.local";
    static final String TEACHER_A_PW = "le-t1-12345";
    static final String TEACHER_B   = "le-teach-b@demo.local";
    static final String TEACHER_B_PW = "le-t2-12345";
    static final String STUDENT_1   = "le-stu-1@demo.local";
    static final String STUDENT_2   = "le-stu-2@demo.local";

    /** Standard start-of-game FEN — reused across board-walkthrough assertions. */
    private static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    UUID adminId;
    UUID teacherAId;
    UUID teacherBId;
    UUID student1Id;
    UUID student2Id;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        adminId    = ensureUser(ADMIN_EMAIL, "LE Admin",     Role.SCHOOL_ADMIN, school.getId(), ADMIN_PW);
        teacherAId = ensureUser(TEACHER_A,   "LE Teacher A", Role.TEACHER,      school.getId(), TEACHER_A_PW);
        teacherBId = ensureUser(TEACHER_B,   "LE Teacher B", Role.TEACHER,      school.getId(), TEACHER_B_PW);
        student1Id = ensureUser(STUDENT_1,   "LE Student 1", Role.STUDENT,      school.getId(), null);
        student2Id = ensureUser(STUDENT_2,   "LE Student 2", Role.STUDENT,      school.getId(), null);
    }

    // ------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------

    @Test
    void adminCreatesLessonWithAllFourBlockKindsAndRoundTrips() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Class A");
        UUID sessionId = createAdHocSession(admin, classId, 1);
        addStudent(admin, classId, student1Id);
        addStudent(admin, classId, student2Id);

        String body = """
                {
                  "title": "Opening principles",
                  "instructions": "Read, watch, try the walkthrough, then answer the MCQ.",
                  "sessionId": "%s",
                  "resources": [
                    {
                      "kind": "prose",
                      "title": "Why develop pieces",
                      "payload": { "markdown": "Develop knights before bishops." }
                    },
                    {
                      "kind": "BOARD_WALKTHROUGH",
                      "payload": {
                        "startingFen": "%s",
                        "moves": ["e2e4", "e7e5", "g1f3"],
                        "orientation": "WHITE",
                        "annotations": [
                          { "ply": 0, "text": "Start here." },
                          { "ply": 3, "text": "After Nf3, black must defend e5." }
                        ]
                      }
                    },
                    {
                      "kind": "MCQ",
                      "payload": {
                        "question": "Which piece controls the most central squares from a central outpost?",
                        "options": ["Knight", "Bishop", "Rook", "Pawn"],
                        "correctIndex": 0,
                        "explanation": "A knight on d5 attacks 8 squares."
                      }
                    },
                    {
                      "kind": "VIDEO",
                      "payload": {
                        "url": "https://www.youtube.com/watch?v=abcDEF12345",
                        "provider": "vimeo"
                      }
                    }
                  ]
                }
                """.formatted(sessionId, START_FEN);

        MvcResult created = mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Opening principles"))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.completions.length()").value(2))
                .andExpect(jsonPath("$.completions[0].status").doesNotExist())
                .andExpect(jsonPath("$.resources.length()").value(4))
                // Position is assigned by index.
                .andExpect(jsonPath("$.resources[0].position").value(0))
                .andExpect(jsonPath("$.resources[1].position").value(1))
                .andExpect(jsonPath("$.resources[2].position").value(2))
                .andExpect(jsonPath("$.resources[3].position").value(3))
                // Kind normalised to uppercase.
                .andExpect(jsonPath("$.resources[0].kind").value("PROSE"))
                .andExpect(jsonPath("$.resources[0].title").value("Why develop pieces"))
                .andExpect(jsonPath("$.resources[0].payload.markdown")
                        .value("Develop knights before bishops."))
                // Board walkthrough: orientation lowercased, moves echoed.
                .andExpect(jsonPath("$.resources[1].kind").value("BOARD_WALKTHROUGH"))
                .andExpect(jsonPath("$.resources[1].payload.orientation").value("white"))
                .andExpect(jsonPath("$.resources[1].payload.moves.length()").value(3))
                .andExpect(jsonPath("$.resources[1].payload.moves[0]").value("e2e4"))
                .andExpect(jsonPath("$.resources[1].payload.annotations.length()").value(2))
                .andExpect(jsonPath("$.resources[1].payload.annotations[1].ply").value(3))
                // MCQ: correctIndex preserved; explanation round-trips.
                .andExpect(jsonPath("$.resources[2].kind").value("MCQ"))
                .andExpect(jsonPath("$.resources[2].payload.options.length()").value(4))
                .andExpect(jsonPath("$.resources[2].payload.correctIndex").value(0))
                .andExpect(jsonPath("$.resources[2].payload.explanation")
                        .value("A knight on d5 attacks 8 squares."))
                // Video: provider DERIVED from host, not trusted from input.
                .andExpect(jsonPath("$.resources[3].kind").value("VIDEO"))
                .andExpect(jsonPath("$.resources[3].payload.url")
                        .value("https://www.youtube.com/watch?v=abcDEF12345"))
                .andExpect(jsonPath("$.resources[3].payload.provider").value("youtube"))
                .andReturn();

        UUID lessonId = UUID.fromString(mapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        // List row carries roster + block counts.
        mvc.perform(get(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rosterSize").value(2))
                .andExpect(jsonPath("$[0].readCount").value(0))
                .andExpect(jsonPath("$[0].blockCount").value(4));

        // GET detail round-trips with the same ordered list.
        mvc.perform(get(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(4))
                .andExpect(jsonPath("$.resources[0].position").value(0))
                .andExpect(jsonPath("$.resources[3].position").value(3));
    }

    @Test
    void updateReplacesResourceListAndEmptyListClearsItAndNullLeavesAlone() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Class B");
        UUID lessonId = createLesson(admin, classId, "Plain lesson");

        // PATCH with a new resources list replaces the (empty) stored list.
        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resources": [
                                  {"kind":"PROSE","payload":{"markdown":"Hello."}}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.resources[0].position").value(0))
                .andExpect(jsonPath("$.resources[0].kind").value("PROSE"));

        // PATCH with an empty list clears everything.
        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resources\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(0));

        // PATCH without the field at all leaves resources alone — re-add
        // one, then patch an unrelated field and confirm the list survives.
        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resources": [
                                  {"kind":"PROSE","payload":{"markdown":"Still there."}}
                                ]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Still here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1));
    }

    @Test
    void patchClearSessionDetachesAnchor() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Class Patch");
        UUID sessionId = createAdHocSession(admin, classId, 2);

        MvcResult res = mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Anchored","sessionId":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andReturn();
        UUID lessonId = UUID.fromString(mapper.readTree(res.getResponse().getContentAsString())
                .get("id").asText());

        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearSession\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").doesNotExist());
    }

    @Test
    void archiveHidesFromListAndBlocksEdits() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Class Archive");
        UUID lessonId = createLesson(admin, classId, "Archivable");

        mvc.perform(delete(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        // Default list excludes archived rows.
        mvc.perform(get(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // includeArchived=true brings it back.
        mvc.perform(get(lessonsUrl(classId) + "?includeArchived=true")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].archivedAt").exists());

        // Further edits are 409.
        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"no-op\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void assignedTeacherCanManageUnassignedCannot() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Class Teacher");
        addStudent(admin, classId, student1Id);
        addTeacher(admin, classId, teacherAId);

        String aTok = login(TEACHER_A, TEACHER_A_PW);
        String bTok = login(TEACHER_B, TEACHER_B_PW);

        // Teacher A (assigned) can create + patch.
        MvcResult r = mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + aTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"teacher-A-lesson\"}"))
                .andExpect(status().isOk())
                .andReturn();
        UUID lessonId = UUID.fromString(mapper.readTree(r.getResponse().getContentAsString())
                .get("id").asText());

        mvc.perform(patch(lessonsUrl(classId) + "/" + lessonId)
                        .header("Authorization", "Bearer " + aTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"teacher-A-lesson v2\"}"))
                .andExpect(status().isOk());

        // Teacher B (same school, not assigned) gets 403 on list and write.
        mvc.perform(get(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + bTok))
                .andExpect(status().isForbidden());
        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + bTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsSessionFromDifferentClass() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classA = createClass(admin, "LE Class Cross A");
        UUID classB = createClass(admin, "LE Class Cross B");
        UUID sessionInB = createAdHocSession(admin, classB, 3);

        mvc.perform(post(lessonsUrl(classA))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"cross-class\",\"sessionId\":\"%s\"}"
                                .formatted(sessionInB)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Validator rejection paths
    // ------------------------------------------------------------------

    @Test
    void rejectsProseWithEmptyMarkdown() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject Prose");

        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad prose",
                                 "resources": [
                                   {"kind":"PROSE","payload":{"markdown":"   "}}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBoardWalkthroughIllegalFen() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject FEN");

        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad fen",
                                 "resources": [
                                   {"kind":"BOARD_WALKTHROUGH","payload":{"startingFen":"not-a-fen"}}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBoardWalkthroughIllegalMove() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject Move");

        // e2e5 is not a legal move from the starting position.
        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad move",
                                 "resources": [
                                   {"kind":"BOARD_WALKTHROUGH","payload":{
                                     "startingFen":"%s",
                                     "moves":["e2e5"]
                                   }}
                                 ]}
                                """.formatted(START_FEN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBoardWalkthroughAnnotationPlyOutOfRange() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject Annot");

        // Two moves played → max valid ply is 2. Annotation with ply=3
        // must fail.
        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad annot",
                                 "resources": [
                                   {"kind":"BOARD_WALKTHROUGH","payload":{
                                     "startingFen":"%s",
                                     "moves":["e2e4","e7e5"],
                                     "annotations":[{"ply":3,"text":"too far"}]
                                   }}
                                 ]}
                                """.formatted(START_FEN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMcqWithOneOption() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject MCQ 1");

        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad mcq",
                                 "resources": [
                                   {"kind":"MCQ","payload":{
                                     "question":"?","options":["Only"],"correctIndex":0
                                   }}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMcqWithDuplicateOptionsCaseInsensitive() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject MCQ Dup");

        // "Knight" and "KNIGHT" normalise to the same lowercase key.
        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"dup mcq",
                                 "resources": [
                                   {"kind":"MCQ","payload":{
                                     "question":"?",
                                     "options":["Knight","KNIGHT","Rook"],
                                     "correctIndex":0
                                   }}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMcqWithOutOfRangeCorrectIndex() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject MCQ idx");

        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad idx",
                                 "resources": [
                                   {"kind":"MCQ","payload":{
                                     "question":"?",
                                     "options":["A","B"],
                                     "correctIndex":2
                                   }}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsVideoHttpScheme() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject Video http");

        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad video scheme",
                                 "resources": [
                                   {"kind":"VIDEO","payload":{
                                     "url":"http://www.youtube.com/watch?v=abc"
                                   }}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsVideoNonAllowlistedHost() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject Video host");

        mvc.perform(post(lessonsUrl(classId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"bad video host",
                                 "resources": [
                                   {"kind":"VIDEO","payload":{
                                     "url":"https://evil.example/thing"
                                   }}
                                 ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownKind() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        UUID classId = createClass(admin, "LE Reject Kind");

        mvc.perform(post(lessonsUrl(classId))
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
