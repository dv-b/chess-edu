package com.chessedu.platform.identity.auth;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.auth.dto.MeResponse;
import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.Membership;
import com.chessedu.platform.identity.model.RefreshToken;
import com.chessedu.platform.identity.model.Role;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.model.UserStatus;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.MembershipRepository;
import com.chessedu.platform.identity.repo.RefreshTokenRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.JwtService;
import com.chessedu.platform.security.PasswordService;
import com.chessedu.platform.security.SecurityProperties;
import com.chessedu.platform.security.TokenHasher;
import com.chessedu.platform.tenancy.School;
import com.chessedu.platform.tenancy.SchoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Login, refresh, logout, and "me" — the workhorse for identity.
 *
 * <p>Refresh-token rotation is the interesting part:
 * <ol>
 *   <li>Every login mints a brand-new {@code familyId}.</li>
 *   <li>A refresh presentation:
 *       <ul>
 *         <li>hashes the presented token,</li>
 *         <li>looks it up by hash,</li>
 *         <li>if found + usable → revokes it, mints a new one in the same family,
 *             and returns access + new refresh;</li>
 *         <li>if found + <em>already revoked</em> → revokes the whole family
 *             (likely token theft) and returns 401.</li>
 *       </ul>
 *   </li>
 * </ol>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final long LOCKOUT_MINUTES = 15;

    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final MembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final SchoolRepository schools;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final TokenHasher hasher;
    private final SecurityProperties props;

    public AuthService(UserRepository users,
                       CredentialsRepository credentials,
                       MembershipRepository memberships,
                       RefreshTokenRepository refreshTokens,
                       SchoolRepository schools,
                       PasswordService passwords,
                       JwtService jwt,
                       TokenHasher hasher,
                       SecurityProperties props) {
        this.users = users;
        this.credentials = credentials;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.schools = schools;
        this.passwords = passwords;
        this.jwt = jwt;
        this.hasher = hasher;
        this.props = props;
    }

    // ========================================================================
    // Login
    // ========================================================================

    @Transactional
    public TokenResponse login(String email, String rawPassword, String ip, String userAgent) {
        String normalized = normalizeEmail(email);
        User user = users.findByEmail(normalized)
                .orElseThrow(() -> bad("Invalid credentials"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            log.info("Login blocked — user {} status={}", user.getId(), user.getStatus());
            throw bad("Invalid credentials");
        }

        Credentials creds = credentials.findByUserId(user.getId())
                .orElseThrow(() -> bad("Invalid credentials"));

        if (creds.isLocked()) {
            log.info("Login blocked — user {} is locked until {}", user.getId(), creds.getLockedUntil());
            throw bad("Account temporarily locked. Try again later.");
        }

        if (!passwords.matches(rawPassword, creds.getPasswordHash())) {
            registerFailure(creds);
            throw bad("Invalid credentials");
        }

        // Success → reset failure counter, maybe rehash, update last-login.
        creds.setFailedAttempts(0);
        creds.setLockedUntil(null);
        if (passwords.needsRehash(creds.getPasswordHash())) {
            creds.setPasswordHash(passwords.hash(rawPassword));
            creds.setPasswordChangedAt(Instant.now());
        }
        credentials.save(creds);

        user.setLastLoginAt(Instant.now());
        users.save(user);

        Membership membership = memberships.findByUserId(user.getId()).orElse(null);
        UUID familyId = UUID.randomUUID();
        return mintTokens(user, membership, familyId, ip, userAgent);
    }

    // ========================================================================
    // Session issuance for externally-verified identities
    // ========================================================================

    /**
     * Mint a fresh JWT pair for a user whose identity was already verified by
     * another channel (Telegram OTT redemption today; the Login Widget later).
     * Same post-login shape as {@link #login}: status gate, last-login stamp,
     * membership lookup, new refresh-token family. No credentials involved —
     * Telegram-signup users have no password row at all.
     */
    @Transactional
    public TokenResponse issueSessionFor(User user, String ip, String userAgent) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.info("Session issuance blocked — user {} status={}", user.getId(), user.getStatus());
            throw bad("Invalid credentials");
        }
        user.setLastLoginAt(Instant.now());
        users.save(user);

        Membership membership = memberships.findByUserId(user.getId()).orElse(null);
        return mintTokens(user, membership, UUID.randomUUID(), ip, userAgent);
    }

    private void registerFailure(Credentials creds) {
        int attempts = creds.getFailedAttempts() + 1;
        creds.setFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            creds.setLockedUntil(Instant.now().plusSeconds(LOCKOUT_MINUTES * 60));
            log.warn("Locked user {} after {} failed attempts", creds.getUserId(), attempts);
        }
        credentials.save(creds);
    }

    // ========================================================================
    // Refresh with rotation + reuse detection
    // ========================================================================

    /**
     * Refresh with rotation + reuse detection.
     *
     * <p><b>Why {@code noRollbackFor = AppException.class}:</b> the reuse-detected
     * and account-suspended branches both call {@link RefreshTokenRepository#revokeFamily}
     * and then throw {@link AppException} to produce a 401. {@code AppException}
     * is a {@link RuntimeException}, so Spring's default rollback rule would
     * undo the family-revocation UPDATE — defeating the whole purpose. The
     * throw is intentional control flow, not a signal to discard bookkeeping,
     * so we opt out of rollback for our own typed exceptions. Every other
     * branch either throws before any write, or commits writes we want kept.
     */
    @Transactional(noRollbackFor = AppException.class)
    public TokenResponse refresh(String presentedToken, String ip, String userAgent) {
        String hash = hasher.hash(presentedToken);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> bad("Invalid refresh token"));

        Instant now = Instant.now();

        if (token.isRevoked()) {
            // Revoked token re-presented — treat as theft, nuke the family.
            int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh reuse detected for user {} — revoked {} tokens in family {}",
                    token.getUserId(), revoked, token.getFamilyId());
            throw bad("Refresh token reuse detected; please sign in again");
        }

        if (token.isExpired(now)) {
            throw bad("Refresh token expired");
        }

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> bad("Invalid refresh token"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw bad("Account not active");
        }

        // Revoke the presented token and mint a successor in the same family.
        token.setRevokedAt(now);
        refreshTokens.save(token);

        Membership membership = memberships.findByUserId(user.getId()).orElse(null);
        TokenResponse response = mintTokens(user, membership, token.getFamilyId(), ip, userAgent);

        // Link old -> new for forensic traceability.
        refreshTokens.findByTokenHash(hasher.hash(response.refreshToken()))
                .ifPresent(newToken -> {
                    token.setReplacedBy(newToken.getId());
                    refreshTokens.save(token);
                });

        return response;
    }

    // ========================================================================
    // Logout
    // ========================================================================

    @Transactional
    public void logout(String presentedRefreshToken) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(hasher.hash(presentedRefreshToken))
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> {
                    t.setRevokedAt(Instant.now());
                    refreshTokens.save(t);
                });
    }

    @Transactional
    public void logoutAll(UUID userId) {
        refreshTokens.revokeAllForUser(userId, Instant.now());
    }

    // ========================================================================
    // Me
    // ========================================================================

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Session no longer valid"));

        Optional<Membership> membership = memberships.findByUserId(userId);
        Optional<School> school = membership.map(Membership::getSchoolId).flatMap(schools::findById);

        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPreferredLocale(),
                membership.map(Membership::getSchoolId).orElse(null),
                school.map(School::getDisplayName).orElse(null),
                membership.map(Membership::getRole).orElse(null),
                user.getPlatformRole()
        );
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private TokenResponse mintTokens(User user, Membership membership, UUID familyId, String ip, String userAgent) {
        Role role = membership != null ? membership.getRole() : null;
        UUID schoolId = membership != null ? membership.getSchoolId() : null;

        String access = jwt.issueAccessToken(new JwtService.AccessTokenClaims(
                user.getId(), user.getEmail(), schoolId, role, user.getPlatformRole()
        ));

        String refreshPlain = hasher.mint();
        RefreshToken refresh = new RefreshToken();
        refresh.setId(UUID.randomUUID());
        refresh.setUserId(user.getId());
        refresh.setFamilyId(familyId);
        refresh.setTokenHash(hasher.hash(refreshPlain));
        refresh.setIssuedAt(Instant.now());
        refresh.setExpiresAt(Instant.now().plus(props.jwt().refreshTokenTtl()));
        refresh.setIp(truncate(ip, 64));
        refresh.setUserAgent(truncate(userAgent, 255));
        refreshTokens.save(refresh);

        return TokenResponse.of(access, refreshPlain, props.jwt().accessTokenTtl().toSeconds());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Canonical email form: trimmed and lower-cased with {@link Locale#ROOT}.
     * Matches what write-paths (seeder, invitation accept, etc.) persist, and
     * lines up with the {@code lower(email)} functional unique index.
     */
    public static String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static AppException bad(String message) {
        return new AppException(ErrorCode.UNAUTHENTICATED, message);
    }
}
