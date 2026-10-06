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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the student Q&amp;A endpoints (Task #67).
 *
 * <p>Full happy-path round-trip: student opens a thread on a homework
 * block, lists their own threads, posts a follow-up reply, watches an
 * admin reply land, and self-resolves. Plus the rejection paths:
 * non-enrolment 404, ownership 404, archived-parent 404, role-gate
 * 403, resolved-thread 409, validation 400.
 *
 * <p>Per-test fresh users so list-length assertions don't bleed
 * between runs in the same Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentBlockQuestionFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "sq-admin@demo.local";
    static final String ADMIN_PW    = "sq-admin-12345";

    static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    UUID schoolId;
    UUID adminId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        schoolId = school.getId();
        adminId  = ensureUser(ADMIN_EMAIL, "SQ Admin", Role.SCHOOL_ADMIN, schoolId, ADMIN_PW);
    }

    // ------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------

    @Test
    void openListReplyResolveRoundTrip() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("rt");
        UUID classId = createClass(admin, "SQ RT " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        String stuTok = login(stu.email, stu.password);

        // 1. Student opens thread.
        MvcResult open = mvc.perform(post(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Why doesn't 1.e4 work for me?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.studentUserId").value(stu.id.toString()))
                .andExpect(jsonPath("$.question.parentKind").value("HOMEWORK"))
                .andExpect(jsonPath("$.question.blockIndex").value(0))
                .andExpect(jsonPath("$.replies.length()").value(0))
                .andReturn();
        UUID qId = UUID.fromString(mapper.readTree(open.getResponse().getContentAsString())
                .get("question").get("id").asText());

        // 2. Per-block list shows the new thread.
        mvc.perform(get(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].question.id").value(qId.toString()));

        // 3. Student follows up with a reply on own thread.
        mvc.perform(post(meReplyUrl(qId))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Actually, I tried 1.e4 e5 2.Nf3.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies.length()").value(1))
                .andExpect(jsonPath("$.replies[0].authorUserId").value(stu.id.toString()))
                // Author == asker → role labels as STUDENT.
                .andExpect(jsonPath("$.replies[0].authorRole").value("STUDENT"));

        // 4. Admin replies via the admin endpoint.
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/questions/" + qId + "/replies")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Compare it to 1.d4. What did Black play?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies.length()").value(2))
                .andExpect(jsonPath("$.replies[1].authorRole").value("TEACHER"));

        // 5. Student fetches single thread and sees both replies.
        mvc.perform(get(meThreadUrl(qId))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies.length()").value(2))
                .andExpect(jsonPath("$.replies[0].authorRole").value("STUDENT"))
                .andExpect(jsonPath("$.replies[1].authorRole").value("TEACHER"));

        // 6. Student self-resolves.
        mvc.perform(post(meResolveUrl(qId))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.resolvedAt").exists())
                .andExpect(jsonPath("$.question.resolvedBy").value(stu.id.toString()));
    }

    @Test
    void lessonOpenAndReplyHappyPath() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("lessrt");
        UUID classId = createClass(admin, "SQ Less " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID lessonId = createSimpleLesson(admin, classId, "Lesson " + stu.tag);

        String stuTok = login(stu.email, stu.password);

        // Open on lesson block 0.
        MvcResult open = mvc.perform(post(meLessonBlockUrl(lessonId, 0))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"What's a fianchetto?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.parentKind").value("LESSON"))
                .andReturn();
        UUID qId = UUID.fromString(mapper.readTree(open.getResponse().getContentAsString())
                .get("question").get("id").asText());

        // Reply.
        mvc.perform(post(meReplyUrl(qId))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Specifically, when do you play g3?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies.length()").value(1));

        // Per-block list shows the thread.
        mvc.perform(get(meLessonBlockUrl(lessonId, 0))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].question.parentKind").value("LESSON"));
    }

    // ------------------------------------------------------------------
    // Resolution semantics
    // ------------------------------------------------------------------

    @Test
    void replyOnSelfResolvedThreadReturns409() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("repclosed");
        UUID classId = createClass(admin, "SQ Closed " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        String stuTok = login(stu.email, stu.password);
        UUID qId = openMyThread(stuTok, hwId, 0, "?");

        // Self-resolve.
        mvc.perform(post(meResolveUrl(qId))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isOk());

        // Now reply → 409.
        mvc.perform(post(meReplyUrl(qId))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"too late\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void doubleSelfResolveReturns409() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("dblclose");
        UUID classId = createClass(admin, "SQ Dbl " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        String stuTok = login(stu.email, stu.password);
        UUID qId = openMyThread(stuTok, hwId, 0, "?");

        mvc.perform(post(meResolveUrl(qId))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isOk());
        mvc.perform(post(meResolveUrl(qId))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // Auth gates
    // ------------------------------------------------------------------

    @Test
    void nonEnrolledStudentCannotOpenOrList() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh enrolled = freshStudent("in");
        Fresh outsider = freshStudent("out");
        UUID classId = createClass(admin, "SQ Enrol " + enrolled.tag);
        addStudent(admin, classId, enrolled.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + enrolled.tag);

        String outTok = login(outsider.email, outsider.password);

        // Open → 404.
        mvc.perform(post(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + outTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"sneaky\"}"))
                .andExpect(status().isNotFound());

        // List → 404.
        mvc.perform(get(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + outTok))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossStudentThreadIdReturns404() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh aliceStu = freshStudent("alice");
        Fresh bobStu = freshStudent("bob");
        UUID classId = createClass(admin, "SQ Cross " + aliceStu.tag);
        addStudent(admin, classId, aliceStu.id);
        addStudent(admin, classId, bobStu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + aliceStu.tag);

        // Alice opens a thread.
        String aliceTok = login(aliceStu.email, aliceStu.password);
        UUID qId = openMyThread(aliceTok, hwId, 0, "alice's question");

        // Bob tries to read it via the questionId route — 404.
        String bobTok = login(bobStu.email, bobStu.password);
        mvc.perform(get(meThreadUrl(qId))
                        .header("Authorization", "Bearer " + bobTok))
                .andExpect(status().isNotFound());

        // Bob also can't reply to Alice's thread.
        mvc.perform(post(meReplyUrl(qId))
                        .header("Authorization", "Bearer " + bobTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hijack\"}"))
                .andExpect(status().isNotFound());

        // Or resolve it.
        mvc.perform(post(meResolveUrl(qId))
                        .header("Authorization", "Bearer " + bobTok))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminTokenOnMeEndpointsReturns403() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("rolegate");
        UUID classId = createClass(admin, "SQ Role " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        // Admin token on a /me/ endpoint → 403 at the @PreAuthorize layer.
        mvc.perform(post(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"shouldn't work\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Archived parent
    // ------------------------------------------------------------------

    @Test
    void archivedHomeworkReturnsNotFoundOnOpenAndList() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("arch");
        UUID classId = createClass(admin, "SQ Arch " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        // Archive the homework.
        mvc.perform(delete("/api/v1/admin/classes/" + classId + "/homework/" + hwId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        String stuTok = login(stu.email, stu.password);

        // Open on archived parent → 404.
        mvc.perform(post(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"?\"}"))
                .andExpect(status().isNotFound());

        // List on archived parent → 404.
        mvc.perform(get(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + stuTok))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void emptyBodyOnOpenOrReplyReturns400() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh stu = freshStudent("val");
        UUID classId = createClass(admin, "SQ Val " + stu.tag);
        addStudent(admin, classId, stu.id);
        UUID hwId = createHwWithBoard(admin, classId, "HW " + stu.tag);

        String stuTok = login(stu.email, stu.password);

        // Empty open → 400.
        mvc.perform(post(meHwBlockUrl(hwId, 0))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // Open with valid body to get a question id, then empty reply → 400.
        UUID qId = openMyThread(stuTok, hwId, 0, "valid");
        mvc.perform(post(meReplyUrl(qId))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Fresh(UUID id, String email, String password, String tag) {}

    private Fresh freshStudent(String tag) {
        String suffix = shortId();
        String email = "sq-stu-" + tag + "-" + suffix + "@demo.local";
        String pw = "sq-stu-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "SQ Stu " + tag, Role.STUDENT, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String meHwBlockUrl(UUID hwId, int blockIndex) {
        return "/api/v1/me/homework/" + hwId + "/blocks/" + blockIndex + "/questions";
    }

    private String meLessonBlockUrl(UUID lessonId, int blockIndex) {
        return "/api/v1/me/lessons/" + lessonId + "/blocks/" + blockIndex + "/questions";
    }

    private String meThreadUrl(UUID qId) {
        return "/api/v1/me/questions/" + qId;
    }

    private String meReplyUrl(UUID qId) {
        return meThreadUrl(qId) + "/replies";
    }

    private String meResolveUrl(UUID qId) {
        return meThreadUrl(qId) + "/resolve";
    }

    private UUID openMyThread(String stuTok, UUID hwId, int blockIndex, String body)
            throws Exception {
        MvcResult r = mvc.perform(post(meHwBlockUrl(hwId, blockIndex))
                        .header("Authorization", "Bearer " + stuTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString())
                .get("question").get("id").asText());
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
