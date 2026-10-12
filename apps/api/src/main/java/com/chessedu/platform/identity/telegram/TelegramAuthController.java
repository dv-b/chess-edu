package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.telegram.dto.TelegramAcceptRequest;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginCodeRequest;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginCodeVerifyRequest;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginCodeView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public (permit-all) endpoints the browser hits without a session to finish
 * a Telegram sign-in:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/telegram/accept} — redeem the OTT from the
 *       bot's deeplink for a JWT pair. Same {@link TokenResponse} shape as
 *       {@code /auth/login} and {@code /auth/invitations/accept}.</li>
 *   <li>{@code POST /api/v1/auth/telegram/login-codes} — "Log in with
 *       Telegram": resolve username / email / phone, DM a 6-digit code.</li>
 *   <li>{@code POST /api/v1/auth/telegram/login-codes/{id}/verify} — swap the
 *       code for the JWT pair (single use, 5 wrong attempts max).</li>
 * </ul>
 *
 * <p>The Login-Widget endpoint ({@code POST /auth/telegram/login}) is deferred
 * until a prod domain exists — see {@code docs/telegram-auth-handoff.md}.
 */
@RestController
@RequestMapping("/api/v1/auth/telegram")
public class TelegramAuthController {

    private final TelegramAuthService telegramAuth;
    private final TelegramLoginCodeService loginCodes;

    public TelegramAuthController(TelegramAuthService telegramAuth,
                                  TelegramLoginCodeService loginCodes) {
        this.telegramAuth = telegramAuth;
        this.loginCodes = loginCodes;
    }

    @PostMapping("/accept")
    public TokenResponse accept(@Valid @RequestBody TelegramAcceptRequest body, HttpServletRequest req) {
        return telegramAuth.accept(body.ott(), ip(req), userAgent(req));
    }

    @PostMapping("/login-codes")
    public TelegramLoginCodeView requestLoginCode(@Valid @RequestBody TelegramLoginCodeRequest body) {
        return loginCodes.request(body.identifier());
    }

    @PostMapping("/login-codes/{id}/verify")
    public TokenResponse verifyLoginCode(@PathVariable("id") UUID id,
                                         @Valid @RequestBody TelegramLoginCodeVerifyRequest body,
                                         HttpServletRequest req) {
        return loginCodes.verify(id, body.code(), ip(req), userAgent(req));
    }

    // Same client-meta extraction as AuthController / InvitationService — the
    // refresh-token row records where the session was minted from.
    private static String ip(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest req) {
        return req.getHeader("User-Agent");
    }
}
