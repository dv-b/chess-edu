package com.chessedu.platform.classes;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the student + parent homework surface end-to-end:
 *
 * <ul>
 *   <li>Student sees only the active homework for the classes they're
 *       enrolled in; an archived assignment is filtered out.</li>
 *   <li>Student can mark DONE (sets submittedAt), SKIPPED (clears
 *       submittedAt), and reset to unmarked via a null status.</li>
 *   <li>Sending an explicit {@code NOT_STARTED} on the student endpoint is
 *       rejected with 400 — the reset path is "null", consistent with
 *       attendance.</li>
 *   <li>A student is 404'd when trying to mark homework in a class they
 *       don't belong to (we don't leak its existence).</li>
 *   <li>Parent with a {@link ParentLink} to a student can read that
 *       student's rollup; without a link the parent gets 404.</li>
 *   <li>A non-student role (school admin) on {@code /me/homework} is 403 at
 *       the pre-authorize layer.</li>
 * </ul>
 *
 * <p><b>Isolation:</b> student and parent users are created fresh <em>per
 * test</em> (via {@link #freshStudent()} / {@link #freshParent()}) rather than
 * shared from {@code @BeforeEach}. Each student's enrolments and each
 * parent's links are private to their test, so list-length assertions don't
 * leak state between tests running in the same context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentParentHomeworkFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CredentialsRepository credentials;
    @Autowired MembershipRepository memberships;
    @Autowired SchoolRepository schools;
    @Autowired ParentLinkRepository parentLinks;
    @Autowired PasswordService passwords;

    static final String ADMIN_EMAIL = "sp-admin@demo.local";
    static final String ADMIN_PW    = "sp-admin-12345";

    UUID schoolId;
    UUID adminId;

    @BeforeEach
    @Transactional
    void seed() {
        School school = schools.findBySlug("demo").orElseThrow();
        schoolId = school.getId();
        adminId  = ensureUser(ADMIN_EMAIL, "SP Admin", Role.SCHOOL_ADMIN, schoolId, ADMIN_PW);
    }

    @Test
    void studentSeesOwnActiveHomeworkOnly() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("see-own");
        Fresh other = freshStudent("see-own-other");

        UUID classA = createClass(admin, "SP Class A " + fs.tag);
        UUID classB = createClass(admin, "SP Class B " + fs.tag); // my student not on this one
        addStudent(admin, classA, fs.id);
        addStudent(admin, classB, other.id);

        UUID hwA1 = createHw(admin, classA, "Active A1");
        UUID hwA2 = createHw(admin, classA, "Active A2");
        createHw(admin, classB, "B-hw-not-mine");

        // Archive one of the student's assignments — it should drop out.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(hwUrl(classA) + "/" + hwA2)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        String student = login(fs.email, fs.password);
        mvc.perform(get("/api/v1/me/homework")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(hwA1.toString()))
                .andExpect(jsonPath("$[0].className").value("SP Class A " + fs.tag))
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    void studentCanMarkDoneSkippedAndReset() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("mark");
        UUID classId = createClass(admin, "SP Class Mark " + fs.tag);
        addStudent(admin, classId, fs.id);
        UUID hwId = createHw(admin, classId, "Markable");

        String student = login(fs.email, fs.password);

        mvc.perform(put("/api/v1/me/homework/" + hwId + "/status")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"studentNote\":\"all puzzles solved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.studentNote").value("all puzzles solved"))
                .andExpect(jsonPath("$.submittedAt").exists());

        mvc.perform(put("/api/v1/me/homework/" + hwId + "/status")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SKIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                // submittedAt is cleared on any non-DONE status.
                .andExpect(jsonPath("$.submittedAt").doesNotExist());

        mvc.perform(put("/api/v1/me/homework/" + hwId + "/status")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist());

        // Admin sees the final state on the admin list — 0 done / 0 skipped.
        mvc.perform(get(hwUrl(classId))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doneCount").value(0))
                .andExpect(jsonPath("$[0].skippedCount").value(0));
    }

    @Test
    void studentCannotMarkNotStartedExplicitly() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("not-started");
        UUID classId = createClass(admin, "SP Class NS " + fs.tag);
        addStudent(admin, classId, fs.id);
        UUID hwId = createHw(admin, classId, "No explicit not-started");

        String student = login(fs.email, fs.password);
        mvc.perform(put("/api/v1/me/homework/" + hwId + "/status")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_STARTED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void studentCannotMarkHomeworkForClassTheyreNotIn() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("outside");
        Fresh other = freshStudent("outside-other");

        UUID classId = createClass(admin, "SP Class Outside " + fs.tag);
        addStudent(admin, classId, other.id); // fs NOT on this roster
        UUID hwId = createHw(admin, classId, "Someone else's");

        String student = login(fs.email, fs.password);
        mvc.perform(put("/api/v1/me/homework/" + hwId + "/status")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentListIsEmptyWhenUnenrolled() throws Exception {
        Fresh fs = freshStudent("empty");
        String student = login(fs.email, fs.password);
        mvc.perform(get("/api/v1/me/homework")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void parentSeesLinkedChildHomeworkAndStrangerDoesNot() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        Fresh fs = freshStudent("family");
        Fresh parent = freshParent("family");
        Fresh stranger = freshParent("stranger");

        UUID classId = createClass(admin, "SP Class Family " + fs.tag);
        addStudent(admin, classId, fs.id);
        UUID hwId = createHw(admin, classId, "Family visible");
        linkParent(parent.id, fs.id, schoolId);

        String parentTok = login(parent.email, parent.password);
        mvc.perform(get("/api/v1/parent/children/" + fs.id + "/homework")
                        .header("Authorization", "Bearer " + parentTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(hwId.toString()));

        // Stranger parent (no link) — 404.
        String strangerTok = login(stranger.email, stranger.password);
        mvc.perform(get("/api/v1/parent/children/" + fs.id + "/homework")
                        .header("Authorization", "Bearer " + strangerTok))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonStudentCannotUseMeHomework() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PW);
        mvc.perform(get("/api/v1/me/homework")
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
        String email = "sp-stu-" + tag + "-" + suffix + "@demo.local";
        String pw = "sp-stu-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "SP Stu " + tag, Role.STUDENT, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private Fresh freshParent(String tag) {
        String suffix = shortId();
        String email = "sp-par-" + tag + "-" + suffix + "@demo.local";
        String pw = "sp-par-" + tag + "-" + suffix;
        UUID id = ensureUser(email, "SP Par " + tag, Role.PARENT, schoolId, pw);
        return new Fresh(id, email, pw, suffix);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

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

    private void addStudent(String access, UUID classId, UUID userId) throws Exception {
        mvc.perform(post("/api/v1/admin/classes/" + classId + "/students")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\"}"))
                .andExpect(status().isNoContent());
    }

    /** Direct-DB seed of a parent ↔ child link; bypasses the /parent/children
     *  flow because that path creates a synthetic child without credentials
     *  we can log in with. {@code parentLinks.save(...)} opens its own tx via
     *  SimpleJpaRepository, no outer {@code @Transactional} needed. */
    void linkParent(UUID parentUserId, UUID childUserId, UUID schoolIdVal) {
        ParentLink link = new ParentLink();
        link.setId(new ParentLink.ParentLinkId(parentUserId, childUserId));
        link.setSchoolId(schoolIdVal);
        link.setCreatedAt(Instant.now());
        parentLinks.save(link);
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
