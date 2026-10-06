package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.TelegramAuthToken;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.TelegramAuthTokenRepository;
import com.chessedu.platform.identity.repo.UserRepository;
import com.chessedu.platform.security.TokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Mint + redeem the one-time token (OTT) that bridges the bot/widget → web
 * handoff. The bot (Task B) calls {@link #mint(UUID)} after creating /
 * identifying a user and hands the raw token to the user inside an
 * {@code /auth/accept?ott=<token>} deeplink; the web auth endpoint (Task C)
 * calls {@link #redeem(String)} to turn it back into a {@link User} and issue a
 * JWT pair.
 *
 * <p>This is unconditional (unlike the bot worker) because the redeem path runs
 * whether or not the bot is enabled. Token secrecy follows the same rule as the
 * other opaque tokens: only the SHA-256 hash is stored; the plaintext lives
 * solely in the deeplink. Single-use, short TTL.
 */
@Service
public class TelegramAuthTokenService {

    private final TelegramAuthTokenRepository tokens;
    private final UserRepository users;
    private final TokenHasher hasher;
    private final TelegramProperties props;

    public TelegramAuthTokenService(TelegramAuthTokenRepository tokens,
                                    UserRepository users,
                                    TokenHasher hasher,
                                    TelegramProperties props) {
        this.tokens = tokens;
        this.users = users;
        this.hasher = hasher;
        this.props = props;
    }

    /** Mint a fresh OTT for {@code userId}; returns the raw (un-hashed) token. */
    @Transactional
    public String mint(UUID userId) {
        Instant now = Instant.now();
        String raw = UUID.randomUUID() + "." + UUID.randomUUID();

        TelegramAuthToken token = new TelegramAuthToken();
        token.setId(UUID.randomUUID());
        token.setUserId(userId);
        token.setTokenHash(hasher.hash(raw));
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(props.ottTtl()));
        tokens.save(token);

        return raw;
    }

    /**
     * Redeem a raw OTT: validate it's known, unused, and unexpired; stamp it
     * used; return the owning {@link User}. Throws {@link AppException} with
     * {@link ErrorCode#UNAUTHENTICATED} on any failure (no distinction between
     * unknown / used / expired — same opaque shape as the other token paths,
     * matching {@code AuthService.bad()}).
     */
    @Transactional
    public User redeem(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Invalid token");
        }
        TelegramAuthToken token = tokens.findByTokenHash(hasher.hash(rawToken))
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Invalid token"));

        Instant now = Instant.now();
        if (!token.isUsable(now)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Invalid token");
        }
        token.setUsedAt(now);
        tokens.save(token);

        return users.findById(token.getUserId())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED, "Invalid token"));
    }
}
