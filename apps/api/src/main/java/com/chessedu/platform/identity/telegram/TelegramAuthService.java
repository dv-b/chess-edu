package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.auth.AuthService;
import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.model.User;
import com.chessedu.platform.identity.repo.TelegramAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Web-side half of the Telegram auth handoff. Unconditional (like
 * {@link TelegramAuthTokenService}) — the deeplink is consumed by the web app
 * whether or not this instance runs the bot worker.
 *
 * <p>One entry point today: {@link #accept(String, String, String)} turns the
 * OTT from {@code /<locale>/auth/accept?ott=…} into a JWT pair. The Login-Widget
 * path (HMAC-verified payload → mint OTT internally → same redemption) lands
 * here too once a prod domain exists for {@code @BotFather /setdomain}.
 */
@Service
public class TelegramAuthService {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthService.class);

    private final TelegramAuthTokenService tokens;
    private final TelegramAccountRepository accounts;
    private final AuthService auth;

    public TelegramAuthService(TelegramAuthTokenService tokens,
                               TelegramAccountRepository accounts,
                               AuthService auth) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.auth = auth;
    }

    /**
     * Redeem a raw OTT and issue a session. Any token failure (unknown, used,
     * expired) and a non-ACTIVE user all surface as {@code UNAUTHENTICATED}
     * with an opaque message — same shape as {@code AuthService.login}.
     *
     * <p>Transactional so the "token stamped used" write rolls back if session
     * issuance throws (e.g. the user was deactivated after the bot minted the
     * OTT) — the token is dead either way, but we don't leave a used-but-no-
     * session row behind.
     */
    @Transactional
    public TokenResponse accept(String rawOtt, String ip, String userAgent) {
        User user = tokens.redeem(rawOtt);

        accounts.findByUserId(user.getId()).ifPresent(account -> {
            account.setLastSeenAt(Instant.now());
            accounts.save(account);
        });

        TokenResponse response = auth.issueSessionFor(user, ip, userAgent);
        log.info("Telegram OTT redeemed: user={}", user.getId());
        return response;
    }
}
