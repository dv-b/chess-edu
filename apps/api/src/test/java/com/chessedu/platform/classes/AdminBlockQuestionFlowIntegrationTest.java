package com.chessedu.platform.classes;

import com.chessedu.platform.classes.model.BlockQuestion;
import com.chessedu.platform.classes.model.BlockQuestionParentKind;
import com.chessedu.platform.classes.repo.BlockQuestionRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the admin Q&amp;A endpoints (Task #66).
 *
 * <p>Student endpoints (open thread / reply / list-own) ship in Task
 * #67, so we seed questions directly via the repository. That keeps
 * this test file focused on the admin / teacher surface:
 *
 * <ul>
 *   <li>Inbox: oldest-first, unresolved-only, archived-parent filter.</li>
 *   <li>Per-block overlays: homework + lesson, both kinds visible.</li>
 *   <li>Single-thread read with cross-class 404 hide-existence.</li>
 *   <li>Reply: success + 409 on resolved + 400 on empty / over-length.</li>
 *   <li>Resolve: success + 409 on already-resolved.</li>
 *   <li>Auth: assigned teacher OK, unassigned teacher 403.</li>
 * </ul>
 *
 * <p>Per-test fresh users (suffixed UUID) so list-length assertions
 * don't bleed between runs in the same Spring context — same shape
 * as {@link HomeworkBlockAttemptFlowIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminBlockQuestionFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;
    @Autowired BlockQuestionRepository questions;

    static final String ADMIN_EMAIL = "bq-admin@demo.local";
    static final String ADMIN_PW    = "bq-admin-12345";

    static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    UUID schoolId;
    UUID adminId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        schoolId = school.getId();
        adminId  = ensureUser(ADMIN_EMAIL, "BQ Admin", Role.SCHOOL_ADMIN, schoolId, ADMIN_PW);
    }

    // ------------------------------------------------------------------
    // Inbox
    // ------------------------------------------------------------------

    @Test
    void inboxReturnsUnresolvedOldestFirstAcrossKinds() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("inbox");
        UUID classId = createClass(admin, "BQ Inbox " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);
        UUID lessonId = createSimpleLesson(admin, classId, "Lesson " + stu.tag);

        // Seed two questions: homework first, lesson second.
        UUID qHw = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id,
                "Why doesn't 1.e4 work for me?");
        // tiny sleep so created_at diverges enough for ordering
        sleepMs(5);
        UUID qLs = seedQuestion(classId, BlockQuestionParentKind.LESSON, lessonId, 0, stu.id,
                "Italian Game — when do I castle?");

        mvc.perform(get(inboxUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Oldest-first ordering — homework was seeded first.
                .andExpect(jsonPath("$[0].question.id").value(qHw.toString()))
                .andExpect(jsonPath("$[0].question.parentKind").value("HOMEWORK"))
                .andExpect(jsonPath("$[0].replyCount").value(0))
                .andExpect(jsonPath("$[0].lastReplyAt").doesNotExist())
                .andExpect(jsonPath("$[1].question.id").value(qLs.toString()))
                .andExpect(jsonPath("$[1].question.parentKind").value("LESSON"));
    }

    @Test
    void inboxFiltersOutResolvedThreads() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("res");
        UUID classId = createClass(admin, "BQ Resolve " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        UUID q = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id,
                "First q");

        // Resolve via the admin endpoint.
        mvc.perform(post(resolveUrl(classId, q))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.resolvedAt").exists())
                .andExpect(jsonPath("$.question.resolvedBy").value(adminId.toString()));

        // Inbox should now be empty for this class.
        mvc.perform(get(inboxUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // But the resolved thread is still readable by id.
        mvc.perform(get(threadUrl(classId, q))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.id").value(q.toString()))
                .andExpect(jsonPath("$.question.resolvedAt").exists());
    }

    @Test
    void inboxFiltersOutThreadsOnArchivedHomework() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("arch");
        UUID classId = createClass(admin, "BQ Arch " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwLive = createHwWithBoard(admin, classId, "HW Live " + stu.tag);
        UUID hwArchived = createHwWithBoard(admin, classId, "HW Arch " + stu.tag);

        seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwLive, 0, stu.id, "live");
        UUID qDead = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwArchived, 0, stu.id, "dead");

        // Archive one of the parents.
        mvc.perform(delete("/api/v1/admin/classes/" + classId + "/homework/" + hwArchived)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        mvc.perform(get(inboxUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].question.parentId").value(hwLive.toString()));

        // The orphaned thread is still readable by id (historical).
        mvc.perform(get(threadUrl(classId, qDead))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Reply
    // ------------------------------------------------------------------

    @Test
    void replyAddsReplyAndStampsThread() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("rep");
        UUID classId = createClass(admin, "BQ Rep " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);
        UUID q = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id,
                "What's the idea behind 3.Bc4?");

        mvc.perform(post(replyUrl(classId, q))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"It targets f7 — Black's weakest square.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.id").value(q.toString()))
                .andExpect(jsonPath("$.replies.length()").value(1))
                .andExpect(jsonPath("$.replies[0].authorUserId").value(adminId.toString()))
                // Admin author isn't the asker → role labels as TEACHER.
                .andExpect(jsonPath("$.replies[0].authorRole").value("TEACHER"))
                .andExpect(jsonPath("$.replies[0].body")
                        .value("It targets f7 — Black's weakest square."));
    }

    @Test
    void replyToResolvedThreadReturns409() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("repclosed");
        UUID classId = createClass(admin, "BQ Closed " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);
        UUID q = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id, "?");

        mvc.perform(post(resolveUrl(classId, q))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        mvc.perform(post(replyUrl(classId, q))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"too late\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void replyEmptyOrOverLengthReturns400() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("repbad");
        UUID classId = createClass(admin, "BQ Bad " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);
        UUID q = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id, "?");

        // Whitespace-only body → 400.
        mvc.perform(post(replyUrl(classId, q))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // 2001 chars → 400.
        String tooLong = "x".repeat(2001);
        mvc.perform(post(replyUrl(classId, q))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Resolve
    // ------------------------------------------------------------------

    @Test
    void resolveAlreadyResolvedThreadReturns409() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("dblres");
        UUID classId = createClass(admin, "BQ Dbl " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);
        UUID q = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id, "?");

        mvc.perform(post(resolveUrl(classId, q))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        mvc.perform(post(resolveUrl(classId, q))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // Per-block overlays
    // ------------------------------------------------------------------

    @Test
    void homeworkBlockOverlayReturnsThreadsForThatBlockOnly() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("ovh");
        UUID classId = createClass(admin, "BQ HOver " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithTwoBoards(admin, classId, "HW two " + stu.tag);

        UUID qBlock0 = seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id, "block 0");
        seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 1, stu.id, "block 1");

        mvc.perform(get(hwBlockUrl(classId, hwId, 0))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].question.id").value(qBlock0.toString()))
                .andExpect(jsonPath("$[0].question.blockIndex").value(0));
    }

    @Test
    void lessonBlockOverlayReturnsThreads() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("ovl");
        UUID classId = createClass(admin, "BQ LOver " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID lessonId = createSimpleLesson(admin, classId, "Lesson " + stu.tag);

        UUID q = seedQuestion(classId, BlockQuestionParentKind.LESSON, lessonId, 0, stu.id,
                "What's a fianchetto?");

        mvc.perform(get(lessonBlockUrl(classId, lessonId, 0))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].question.id").value(q.toString()))
                .andExpect(jsonPath("$[0].question.parentKind").value("LESSON"));
    }

    @Test
    void crossClassThreadIdReturns404() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("cross");
        UUID classA = createClass(admin, "BQ A " + stu.tag);
        UUID classB = createClass(admin, "BQ B " + stu.tag);
        addStudent(admin, classA, stu.id);
        UUID hwId = createHwWithBoard(admin, classA, "HW A " + stu.tag);
        UUID q = seedQuestion(classA, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id, "?");

        // Class B's URL with class A's question id → 404 (hides existence).
        mvc.perform(get(threadUrl(classB, q))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());

        // Replying via the wrong-class URL also 404s.
        mvc.perform(post(replyUrl(classB, q))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    @Test
    void unassignedTeacherCannotReadInbox() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("auth");
        Fresh teachIn = freshTeacher("in");
        Fresh teachOut = freshTeacher("out");
        UUID classId = createClass(admin, "BQ Auth " + stu.tag);
        addStudent(admin, classId, stu.id);
        addTeacher(admin, classId, teachIn.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);
        seedQuestion(classId, BlockQuestionParentKind.HOMEWORK, hwId, 0, stu.id, "?");

        // Assigned teacher — OK.
        String inTok = login(teachIn.email, teachIn.password);
        mvc.perform(get(inboxUrl(classId))
                        .header("Authorization", "Bearer " + inTok))
                .andExpect(status().isOk());

        // Unassigned teacher in the same school — 403.
        String outTok = login(teachOut.email, teachOut.password);
        mvc.perform(get(inboxUrl(classId))
                        .header("Authorization", "Bearer " + outTok))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Fresh(UUID id, String email, String password, String tag) {}

    private Fresh freshStudent(String tag) {
        String suffix = shortId();
        String email = "bq-stu-" + tag + "-" + suffix + "@demo.local";
        String pw = "bq-stu-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "BQ Stu " + tag, Role.STUDENT, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private Fresh freshTeacher(String tag) {
        String suffix = shortId();
        String email = "bq-teach-" + tag + "-" + suffix + "@demo.local";
        String pw = "bq-teach-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "BQ Teach " + tag, Role.TEACHER, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String inboxUrl(UUID classId) {
        return "/api/v1/admin/classes/" + classId + "/questions";
    }

    private String threadUrl(UUID classId, UUID questionId) {
        return inboxUrl(classId) + "/" + questionId;
    }

    private String replyUrl(UUID classId, UUID questionId) {
        return threadUrl(classId, questionId) + "/replies";
    }

    private String resolveUrl(UUID classId, UUID questionId) {
        return threadUrl(classId, questionId) + "/resolve";
    }

    private String hwBlockUrl(UUID classId, UUID hwId, int blockIndex) {
        return "/api/v1/admin/classes/" + classId + "/homework/" + hwId
                + "/blocks/" + blockIndex + "/questions";
    }

    private String lessonBlockUrl(UUID classId, UUID lessonId, int blockIndex) {
        return "/api/v1/admin/classes/" + classId + "/lessons/" + lessonId
                + "/blocks/" + blockIndex + "/questions";
    }

    /** Direct-to-DB seeding so we don't depend on the not-yet-built student endpoints. */
    private UUID seedQuestion(UUID classId,
                              BlockQuestionParentKind parentKind,
                              UUID parentId,
                              int blockIndex,
                              UUID studentUserId,
                              String body) {
        BlockQuestion q = new BlockQuestion();
        q.setId(UUID.randomUUID());
        q.setClassId(classId);
        q.setParentKind(parentKind);
        q.setParentId(parentId);
        q.setBlockIndex(blockIndex);
        q.setStudentUserId(studentUserId);
        q.setBody(body);
        questions.save(q);
        return q.getId();
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

    private UUID createHwWithBoard(String access, UUID classId, String title) throws Exception {
        String body = """
                {"title":"%s",
                 "resources":[{
                   "kind":"BOARD",
                   "payload":{"startingFen":"%s"}
                 }]}
                """.formatted(title, START_FEN);
        return postCreate(access, "/api/v1/admin/classes/" + classId + "/homework", body);
    }

    private UUID createHwWithTwoBoards(String access, UUID classId, String title) throws Exception {
        String body = """
                {"title":"%s",
                 "resources":[
                   {"kind":"BOARD","payload":{"startingFen":"%s"}},
                   {"kind":"BOARD","payload":{"startingFen":"%s"}}
                 ]}
                """.formatted(title, START_FEN, START_FEN);
        return postCreate(access, "/api/v1/admin/classes/" + classId + "/homework", body);
    }

    private UUID createSimpleLesson(String access, UUID classId, String title) throws Exception {
        String body = """
                {"title":"%s",
                 "resources":[{
                   "kind":"PROSE",
                   "payload":{"markdown":"hello"}
                 }]}
                """.formatted(title);
        return postCreate(access, "/api/v1/admin/classes/" + classId + "/lessons", body);
    }

    private UUID postCreate(String access, String url, String body) throws Exception {
        MvcResult res = mvc.perform(post(url)
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
