package com.chessedu.platform.bootstrap;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Seeds a demo school + SCHOOL_ADMIN + STUDENT on startup,
 * <em>local profile only</em>. Idempotent — safe to restart without
 * duplicating data.
 *
 * <p>After first boot:
 * <pre>
 *   Admin:
 *     email:    admin@demo.local
 *     password: admin12345
 *
 *   Student (dev only — students normally have no credentials; see
 *   {@code ChildrenService}):
 *     email:    student@demo.local
 *     password: student12345
 * </pre>
 * Log in via POST /api/v1/auth/login to get tokens. The admin still
 * has to create a class, enrol the student, and assign homework
 * before the student's {@code /student} shell has anything to show.
 */
@Component
@Profile("local")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String ADMIN_EMAIL = "admin@demo.local";
    private static final String ADMIN_PASSWORD = "admin12345";
    private static final String STUDENT_EMAIL = "student@demo.local";
    private static final String STUDENT_PASSWORD = "student12345";
    private static final String DEMO_SLUG = "demo";

    private final SchoolRepository schools;
    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final MembershipRepository memberships;
    private final PasswordService passwords;

    public DemoDataSeeder(SchoolRepository schools,
                          UserRepository users,
                          CredentialsRepository credentials,
                          MembershipRepository memberships,
                          PasswordService passwords) {
        this.schools = schools;
        this.users = users;
        this.credentials = credentials;
        this.memberships = memberships;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        School demo = schools.findBySlug(DEMO_SLUG).orElse(null);
        if (demo == null) {
            log.warn("Demo school '{}' not found — V001 migration may not have run", DEMO_SLUG);
            return;
        }

        seedUser(demo, ADMIN_EMAIL, ADMIN_PASSWORD, "Demo Admin", Role.SCHOOL_ADMIN);
        seedUser(demo, STUDENT_EMAIL, STUDENT_PASSWORD, "Demo Student", Role.STUDENT);
    }

    /**
     * Ensures a user with the given email exists, is credentialed with the
     * supplied password, and has an active membership in {@code school} under
     * {@code role}. Each step independently idempotent so adding a new seed
     * account to an already-booted dev DB fills in only the missing pieces.
     */
    private void seedUser(School school,
                          String email,
                          String password,
                          String displayName,
                          Role role) {
        User user = users.findByEmail(email).orElse(null);
        boolean fresh = user == null;
        if (fresh) {
            user = new User();
            user.setId(UUID.randomUUID());
            user.setEmail(email);
            user.setDisplayName(displayName);
            user.setPreferredLocale("uz");
            user.setStatus(UserStatus.ACTIVE);
            users.save(user);
        }

        boolean hasCreds = credentials.findByUserId(user.getId()).isPresent();
        if (!hasCreds) {
            Credentials creds = new Credentials();
            creds.setUserId(user.getId());
            creds.setPasswordHash(passwords.hash(password));
            creds.setPasswordChangedAt(Instant.now());
            creds.setFailedAttempts(0);
            credentials.save(creds);
        }

        // The Membership model assumes one role per user (see Role.java's
        // "role switching is intentionally not supported" comment), so a
        // single findByUserId is enough to tell whether this user is already
        // wired to a school.
        boolean hasMembership = memberships.findByUserId(user.getId()).isPresent();
        if (!hasMembership) {
            Membership membership = new Membership();
            membership.setId(UUID.randomUUID());
            membership.setUserId(user.getId());
            membership.setSchoolId(school.getId());
            membership.setRole(role);
            membership.setStatus(MembershipStatus.ACTIVE);
            memberships.save(membership);
        }

        if (fresh) {
            log.info("Seeded demo user: role={} email={} password={} school={}",
                    role, email, password, school.getSlug());
        } else if (!hasCreds || !hasMembership) {
            log.info("Backfilled demo user: role={} email={} (creds+membership ensured)",
                    role, email);
        } else {
            log.info("Demo user '{}' already exists — skipping", email);
        }
    }
}
