package com.chessedu.platform.identity.telegram;

import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.identity.telegram.dto.TelegramAcceptRequest;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginPollRequest;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginPollResponse;
import com.chessedu.platform.identity.telegram.dto.TelegramLoginRequestView;
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
 *   <li>{@code POST /api/v1/auth/telegram/login-requests} — start a "Log in
 *       with Telegram" handshake (returns the {@code t.me} deeplink).</li>
 *   <li>{@code POST /api/v1/auth/telegram/login-requests/{id}/poll} — ask
 *       whether the bot confirmed it; the first poll after confirmation
 *       carries the JWT pair and consumes the request.</li>
 * </ul>
 *
 * <p>The Login-Widget endpoint ({@code POST /auth/telegram/login}) is deferred
 * until a prod domain exists — see {@code docs/telegram-auth-handoff.md}.
 */
@RestController
@RequestMapping("/api/v1/auth/telegram")
public class TelegramAuthController {

    private final TelegramAuthService telegramAuth;
    private final TelegramLoginRequestService loginRequests;

    public TelegramAuthController(TelegramAuthService telegramAuth,
                                  TelegramLoginRequestService loginRequests) {
        this.telegramAuth = telegramAuth;
        this.loginRequests = loginRequests;
    }

    @PostMapping("/accept")
    public TokenResponse accept(@Valid @RequestBody TelegramAcceptRequest body, HttpServletRequest req) {
        return telegramAuth.accept(body.ott(), ip(req), userAgent(req));
    }

    @PostMapping("/login-requests")
    public TelegramLoginRequestView createLoginRequest() {
        return loginRequests.create();
    }

    @PostMapping("/login-requests/{id}/poll")
    public TelegramLoginPollResponse pollLoginRequest(@PathVariable("id") UUID id,
                                                      @Valid @RequestBody TelegramLoginPollRequest body,
                                                      HttpServletRequest req) {
        return loginRequests.poll(id, body.clientSecret(), ip(req), userAgent(req));
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
