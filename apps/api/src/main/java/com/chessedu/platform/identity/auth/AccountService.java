package com.chessedu.platform.identity.auth;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.auth.dto.MeResponse;
import com.chessedu.platform.identity.model.Credentials;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.CredentialsRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Self-service account settings for the signed-in user (auth roadmap item 3):
 * pick / change a username, and set or change a password.
 *
 * <p>Setting a password when none exists is the Telegram-signup user's
 * fallback — they signed up with a phone, never had a {@link Credentials}
 * row, and this is how they get username + password as a second way in.
 * Changing an existing password requires the current one.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** Mirrors the V014 CHECK constraint exactly. */
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9_.]{2,31}$");

    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final PasswordService passwords;
    private final AuthService auth;

    public AccountService(UserRepository users,
                          CredentialsRepository credentials,
                          PasswordService passwords,
                          AuthService auth) {
        this.users = users;
        this.credentials = credentials;
        this.passwords = passwords;
        this.auth = auth;
    }

    @Transactional
    public MeResponse setUsername(UUID userId, String rawUsername) {
        String username = AuthService.normalizeUsername(rawUsername);
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "Username must be 3–32 characters: letters, digits, '.' or '_'");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Session no longer valid"));

        if (!username.equals(user.getUsername()) && users.existsByUsername(username)) {
            throw AppException.conflict("That username is already taken");
        }
        user.setUsername(username);
        users.save(user);
        log.info("Username set for user {}", userId);
        return auth.me(userId);
    }

    @Transactional
    public MeResponse setPassword(UUID userId, String currentPassword, String newPassword) {
        User user = users.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Session no longer valid"));

        Credentials creds = credentials.findByUserId(userId).orElse(null);
        Instant now = Instant.now();

        if (creds == null) {
            // First password (Telegram-signup user). No current password to check.
            creds = new Credentials();
            creds.setUserId(user.getId());
            creds.setFailedAttempts(0);
            log.info("First password set for user {}", userId);
        } else {
            if (currentPassword == null || !passwords.matches(currentPassword, creds.getPasswordHash())) {
                throw new AppException(ErrorCode.UNAUTHENTICATED, "Current password is incorrect");
            }
            creds.setFailedAttempts(0);
            creds.setLockedUntil(null);
            log.info("Password changed for user {}", userId);
        }
        creds.setPasswordHash(passwords.hash(newPassword));
        creds.setPasswordChangedAt(now);
        credentials.save(creds);
        return auth.me(userId);
    }
}
