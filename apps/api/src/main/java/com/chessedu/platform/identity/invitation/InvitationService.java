package com.chessedu.platform.identity.invitation;

import com.chessedu.platform.common.config.WebProperties;
import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.email.EmailService;
import com.chessedu.platform.identity.auth.AuthService;
import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.invitation.dto.AcceptInvitationRequest;
import com.chessedu.platform.identity.invitation.dto.InvitationPreview;
import com.chessedu.platform.identity.invitation.dto.InvitationView;
import com.chessedu.platform.identity.invitation.dto.IssueInvitationRequest;
import com.chessedu.platform.identity.model.BotState;
import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.DeliveryChannel;
import com.chessedu.platform.identity.model.Invitation;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.MembershipStatus;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.RefreshToken;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.InvitationRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.RefreshTokenRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.identity.telegram.PhoneNumbers;
import com.chessedu.platform.identity.telegram.TelegramProperties;
import com.chessedu.platform.security.AuthenticatedUser;
import com.chessedu.platform.security.JwtService;
import com.chessedu.platform.security.PasswordService;
import com.chessedu.platform.security.SecurityProperties;
import com.chessedu.platform.security.TokenHasher;
import com.chessedu.platform.tenancy.School;
import com.chessedu.platform.tenancy.SchoolRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end invitation flow.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #issue} — validates the caller's authority, mints a token,
 *       persists the invitation, and fires the email.</li>
 *   <li>{@link #preview} — surfaces the minimum info the accept page needs,
 *       using the raw token presented in the URL.</li>
 *   <li>{@link #accept} — creates the user/credentials/membership, marks the
 *       invitation as used, and auto-logs the user in.</li>
 *   <li>{@link #list} / {@link #revoke} — admin housekeeping.</li>
 * </ul>
 *
 * <p>Token secrecy: we never store the raw token — only its SHA-256 hash.
 * A raw token is returned (via the email URL) exactly once at issue time.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    /** School-scoped roles SCHOOL_ADMINs are allowed to invite in M2. */
    private static final Set<Role> SCHOOL_ADMIN_ALLOWED_ROLES =
            Set.of(Role.SCHOOL_ADMIN, Role.TEACHER, Role.PARENT);

    /** Platform role keyword recognised in the invitation "role" column. */
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final MembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final SchoolRepository schools;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final TokenHasher hasher;
    private final EmailService email;
    private final InvitationProperties invitationProps;
    private final SecurityProperties securityProps;
    private final WebProperties webProps;
    private final TelegramProperties telegramProps;

    public InvitationService(InvitationRepository invitations,
                             UserRepository users,
                             CredentialsRepository credentials,
                             MembershipRepository memberships,
                             RefreshTokenRepository refreshTokens,
                             SchoolRepository schools,
                             PasswordService passwords,
                             JwtService jwt,
                             TokenHasher hasher,
                             EmailService email,
                             InvitationProperties invitationProps,
                             SecurityProperties securityProps,
                             WebProperties webProps,
                             TelegramProperties telegramProps) {
        this.invitations = invitations;
        this.users = users;
        this.credentials = credentials;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.schools = schools;
        this.passwords = passwords;
        this.jwt = jwt;
        this.hasher = hasher;
        this.email = email;
        this.invitationProps = invitationProps;
        this.securityProps = securityProps;
        this.webProps = webProps;
        this.telegramProps = telegramProps;
    }

    // ========================================================================
    // Issue
    // ========================================================================

    @Transactional
    public InvitationView issue(AuthenticatedUser actor, IssueInvitationRequest req) {
        if (actor == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }

        String normalizedEmail = AuthService.normalizeEmail(req.email());
        String roleValue = req.role();
        boolean isPlatformRole = PLATFORM_ADMIN_ROLE.equals(roleValue);

        UUID targetSchoolId = resolveTargetSchool(actor, req, isPlatformRole);
        School school = null;
        if (targetSchoolId != null) {
            school = schools.findById(targetSchoolId)
                    .orElseThrow(() -> AppException.notFound("School"));
        }

        enforceCallerAuthority(actor, roleValue, targetSchoolId);

        DeliveryChannel channel = req.deliveryChannel() == null
                ? DeliveryChannel.EMAIL
                : DeliveryChannel.valueOf(req.deliveryChannel());
        if (channel == DeliveryChannel.TELEGRAM) {
            // (Non-student only is already guaranteed: enforceCallerAuthority
            // above forbids STUDENT invitations on every channel.)
            if (telegramProps.botUsername() == null || telegramProps.botUsername().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "Telegram bot is not configured (chessedu.telegram.bot-username)");
            }
        }

        String phone = null;
        if (req.phone() != null && !req.phone().isBlank()) {
            phone = PhoneNumbers.normalize(req.phone());
            if (phone == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "Phone must be an international number like +998901234567");
            }
            boolean phonePending = invitations.findPendingByPhone(phone).stream()
                    .anyMatch(i -> !i.isExpired(Instant.now()));
            if (phonePending) {
                throw AppException.conflict("A pending invitation already exists for this phone number");
            }
        }

        // Already a user? Block — they should use password-reset instead.
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw AppException.conflict("A user with that email already exists");
        }

        // Pending duplicate? Block — admin should revoke + re-issue explicitly.
        Optional<Invitation> duplicate = isPlatformRole
                ? invitations.findPendingPlatformByEmail(normalizedEmail)
                : invitations.findPendingByEmailAndSchoolId(normalizedEmail, targetSchoolId);
        if (duplicate.isPresent()) {
            throw AppException.conflict("A pending invitation already exists for this email");
        }

        // Mint the plaintext token; store only its hash.
        String rawToken = hasher.mint();
        Instant now = Instant.now();

        Invitation invitation = new Invitation();
        invitation.setId(UUID.randomUUID());
        invitation.setSchoolId(targetSchoolId);
        invitation.setRole(roleValue);
        invitation.setEmail(normalizedEmail);
        invitation.setDisplayName(req.displayName().trim());
        invitation.setInvitedBy(actor.userId());
        invitation.setTokenHash(hasher.hash(rawToken));
        invitation.setIssuedAt(now);
        invitation.setExpiresAt(now.plus(invitationProps.ttl()));
        invitation.setDeliveryChannel(channel);
        invitation.setBotState(channel == DeliveryChannel.TELEGRAM ? BotState.PENDING : null);
        invitation.setPhone(phone);
        invitations.save(invitation);

        String botDeeplink = null;
        if (channel == DeliveryChannel.TELEGRAM) {
            // No email: the admin forwards the deeplink themselves (typically
            // in Telegram). Shown once — the raw token is never persisted.
            botDeeplink = telegramDeeplink(rawToken);
        } else {
            // Fire-and-forget email. Failures are logged but don't fail the
            // request (the admin can re-send).
            dispatchInvitationEmail(invitation, school, rawToken, req.preferredLocale(), actor);
        }

        log.info("Invitation issued: id={} role={} schoolId={} email={} channel={} by={}",
                invitation.getId(), roleValue, targetSchoolId, normalizedEmail, channel, actor.userId());

        return toView(invitation, school, now, botDeeplink);
    }

    /** {@code https://t.me/<bot>?start=INV_<token>} — what the bot's /start handler expects. */
    private String telegramDeeplink(String rawToken) {
        return "https://t.me/" + telegramProps.botUsername() + "?start=INV_" + rawToken;
    }

    private UUID resolveTargetSchool(AuthenticatedUser actor, IssueInvitationRequest req, boolean isPlatformRole) {
        if (isPlatformRole) {
            // PLATFORM_ADMIN invitations are not school-scoped.
            return null;
        }
        if (actor.platformRole() == PlatformRole.PLATFORM_ADMIN) {
            if (req.schoolId() == null) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "schoolId is required when a platform admin invites a school user");
            }
            return req.schoolId();
        }
        if (actor.schoolId() == null) {
            throw AppException.forbidden("Caller has no school context");
        }
        // SCHOOL_ADMIN callers: ignore any schoolId in the payload, always use theirs.
        return actor.schoolId();
    }

    private void enforceCallerAuthority(AuthenticatedUser actor, String roleValue, UUID targetSchoolId) {
        boolean isPlatform = actor.platformRole() == PlatformRole.PLATFORM_ADMIN;

        if (PLATFORM_ADMIN_ROLE.equals(roleValue)) {
            if (!isPlatform) {
                throw AppException.forbidden("Only platform admins may invite other platform admins");
            }
            return;
        }

        // From here: school-scoped role.
        Role role;
        try {
            role = Role.valueOf(roleValue);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Unknown role: " + roleValue);
        }

        if (role == Role.STUDENT) {
            // Students are created via parent flows in M3; not invite-eligible yet.
            throw AppException.forbidden("Student invitations are not supported yet");
        }

        if (isPlatform) {
            // Platform admin can act on any school.
            return;
        }

        if (actor.role() != Role.SCHOOL_ADMIN) {
            throw AppException.forbidden("Only school admins may issue invitations");
        }
        if (!SCHOOL_ADMIN_ALLOWED_ROLES.contains(role)) {
            throw AppException.forbidden("School admins may not invite role " + role);
        }
        if (!actor.schoolId().equals(targetSchoolId)) {
            throw AppException.forbidden("Caller cannot invite to another school");
        }
    }

    // ========================================================================
    // Preview (public)
    // ========================================================================

    @Transactional(readOnly = true)
    public InvitationPreview preview(String rawToken) {
        Invitation invite = loadUsable(rawToken);
        School school = invite.getSchoolId() != null
                ? schools.findById(invite.getSchoolId()).orElse(null)
                : null;
        return new InvitationPreview(
                invite.getEmail(),
                invite.getDisplayName(),
                invite.getRole(),
                school != null ? school.getDisplayName() : null,
                school != null ? school.getDefaultLocale() : "uz",
                invite.getExpiresAt()
        );
    }

    // ========================================================================
    // Accept (public) — creates the user, marks invite, auto-logs in.
    // ========================================================================

    @Transactional
    public TokenResponse accept(AcceptInvitationRequest req, HttpServletRequest http) {
        Invitation invite = loadUsable(req.token());

        String normalizedEmail = AuthService.normalizeEmail(invite.getEmail());

        // Paranoid check: if the email is now somehow claimed (race or manual
        // seed), reject rather than overwriting.
        if (users.findByEmail(normalizedEmail).isPresent()) {
            throw AppException.conflict("A user with that email already exists");
        }

        String effectiveLocale = pickLocale(req.preferredLocale());

        // --- 1. Create the user --------------------------------------------
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(normalizedEmail);
        user.setDisplayName(effectiveDisplayName(req.displayName(), invite.getDisplayName()));
        user.setPreferredLocale(effectiveLocale);
        user.setStatus(UserStatus.ACTIVE);
        if (PLATFORM_ADMIN_ROLE.equals(invite.getRole())) {
            user.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        }
        user.setLastLoginAt(Instant.now());
        users.save(user);

        // --- 2. Store credentials ------------------------------------------
        Credentials creds = new Credentials();
        creds.setUserId(user.getId());
        creds.setPasswordHash(passwords.hash(req.password()));
        creds.setPasswordChangedAt(Instant.now());
        creds.setFailedAttempts(0);
        credentials.save(creds);

        // --- 3. Membership (school-scoped roles) ---------------------------
        Membership membership = null;
        if (!PLATFORM_ADMIN_ROLE.equals(invite.getRole()) && invite.getSchoolId() != null) {
            Role role = Role.valueOf(invite.getRole());
            membership = new Membership();
            membership.setId(UUID.randomUUID());
            membership.setUserId(user.getId());
            membership.setSchoolId(invite.getSchoolId());
            membership.setRole(role);
            membership.setStatus(MembershipStatus.ACTIVE);
            memberships.save(membership);
        }

        // --- 4. Mark invitation used ---------------------------------------
        invite.setAcceptedAt(Instant.now());
        invite.setAcceptedUser(user.getId());
        invitations.save(invite);

        // --- 5. Auto-login -------------------------------------------------
        return mintTokens(user, membership, http);
    }

    // ========================================================================
    // List / Revoke
    // ========================================================================

    @Transactional(readOnly = true)
    public List<InvitationView> listForSchool(UUID schoolId) {
        if (schoolId == null) {
            return List.of();
        }
        School school = schools.findById(schoolId).orElse(null);
        Instant now = Instant.now();
        return invitations.findBySchoolIdOrderByIssuedAtDesc(schoolId).stream()
                .map(inv -> toView(inv, school, now))
                .sorted(Comparator.comparing(InvitationView::issuedAt).reversed())
                .toList();
    }

    @Transactional
    public void revoke(AuthenticatedUser actor, UUID invitationId) {
        Invitation invite = invitations.findById(invitationId)
                .orElseThrow(() -> AppException.notFound("Invitation"));

        boolean isPlatform = actor.platformRole() == PlatformRole.PLATFORM_ADMIN;
        if (!isPlatform) {
            if (actor.role() != Role.SCHOOL_ADMIN) {
                throw AppException.forbidden("Only school admins may revoke invitations");
            }
            if (invite.getSchoolId() == null || !invite.getSchoolId().equals(actor.schoolId())) {
                throw AppException.forbidden("Caller cannot revoke this invitation");
            }
        }

        if (invite.getAcceptedAt() != null) {
            throw new AppException(ErrorCode.GONE, "Invitation already accepted");
        }

        invitations.delete(invite);
        log.info("Invitation revoked: id={} by={}", invitationId, actor.userId());
    }

    // ========================================================================
    // Internals
    // ========================================================================

    private Invitation loadUsable(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Missing invitation token");
        }
        Invitation invite = invitations.findByTokenHash(hasher.hash(rawToken))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Invitation not found"));

        Instant now = Instant.now();
        if (invite.isAccepted()) {
            throw new AppException(ErrorCode.GONE, "Invitation already accepted");
        }
        if (invite.isExpired(now)) {
            throw new AppException(ErrorCode.GONE, "Invitation expired");
        }
        return invite;
    }

    private TokenResponse mintTokens(User user, Membership membership, HttpServletRequest http) {
        Role role = membership != null ? membership.getRole() : null;
        UUID schoolId = membership != null ? membership.getSchoolId() : null;

        String access = jwt.issueAccessToken(new JwtService.AccessTokenClaims(
                user.getId(), user.getEmail(), schoolId, role, user.getPlatformRole()
        ));

        String refreshPlain = hasher.mint();
        RefreshToken refresh = new RefreshToken();
        refresh.setId(UUID.randomUUID());
        refresh.setUserId(user.getId());
        refresh.setFamilyId(UUID.randomUUID());
        refresh.setTokenHash(hasher.hash(refreshPlain));
        refresh.setIssuedAt(Instant.now());
        refresh.setExpiresAt(Instant.now().plus(securityProps.jwt().refreshTokenTtl()));
        refresh.setIp(truncate(ip(http), 64));
        refresh.setUserAgent(truncate(http != null ? http.getHeader("User-Agent") : null, 255));
        refreshTokens.save(refresh);

        return TokenResponse.of(access, refreshPlain, securityProps.jwt().accessTokenTtl().toSeconds());
    }

    private static String ip(HttpServletRequest http) {
        if (http == null) return null;
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return http.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String pickLocale(String userChoice) {
        if (userChoice != null && !userChoice.isBlank()) {
            return userChoice;
        }
        // Accept falls back to Uzbek — the primary audience. The preview route
        // exposes the school's default, but the user can still override here.
        return "uz";
    }

    private static String effectiveDisplayName(String override, String fallback) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return fallback;
    }

    private InvitationView toView(Invitation inv, School school, Instant now) {
        return toView(inv, school, now, null);
    }

    private InvitationView toView(Invitation inv, School school, Instant now, String botDeeplink) {
        boolean expired = inv.getAcceptedAt() == null && inv.isExpired(now);
        return new InvitationView(
                inv.getId(),
                inv.getSchoolId(),
                school != null ? school.getDisplayName() : null,
                inv.getRole(),
                inv.getEmail(),
                inv.getDisplayName(),
                inv.getInvitedBy(),
                inv.getIssuedAt(),
                inv.getExpiresAt(),
                inv.getAcceptedAt(),
                expired,
                inv.getDeliveryChannel() != null ? inv.getDeliveryChannel().name() : DeliveryChannel.EMAIL.name(),
                inv.getBotState() != null ? inv.getBotState().name() : null,
                botDeeplink,
                inv.getPhone(),
                inv.getDeliveryChannel() == DeliveryChannel.TELEGRAM ? telegramProps.botUsername() : null
        );
    }

    private void dispatchInvitationEmail(Invitation invite, School school, String rawToken,
                                         String preferredLocale, AuthenticatedUser actor) {
        String locale = preferredLocale != null && !preferredLocale.isBlank()
                ? preferredLocale
                : (school != null ? school.getDefaultLocale() : "uz");
        Locale resolved = resolveLocale(locale);

        String acceptUrl = webProps.baseUrl() + "/" + locale
                + "/auth/invitations/" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        String expiresLocal = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm", resolved)
                .withZone(ZoneId.of(school != null && school.getTimezone() != null
                        ? school.getTimezone()
                        : "Asia/Tashkent"))
                .format(invite.getExpiresAt());

        String roleLabel = invite.getRole();  // Raw enum name; translated client-side later.

        email.sendInvitation(new EmailService.InvitationEmail(
                invite.getEmail(),
                invite.getDisplayName(),
                actor != null ? actor.email() : null,
                school != null ? school.getDisplayName() : null,
                roleLabel,
                acceptUrl,
                expiresLocal
        ), resolved);
    }

    private static Locale resolveLocale(String code) {
        if (code == null) return Locale.ENGLISH;
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "ru" -> new Locale("ru");
            case "uz" -> new Locale("uz");
            default -> Locale.ENGLISH;
        };
    }
}
