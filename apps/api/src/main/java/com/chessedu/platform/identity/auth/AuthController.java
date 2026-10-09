package com.chessedu.platform.identity.auth;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.auth.dto.LoginRequest;
import com.chessedu.platform.identity.auth.dto.MeResponse;
import com.chessedu.platform.identity.auth.dto.RefreshRequest;
import com.chessedu.platform.identity.auth.dto.SetPasswordRequest;
import com.chessedu.platform.identity.auth.dto.SetUsernameRequest;
import com.chessedu.platform.identity.auth.dto.TokenResponse;
import com.chessedu.platform.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final AccountService account;

    public AuthController(AuthService auth, AccountService account) {
        this.auth = auth;
        this.account = account;
    }

    private static void requirePrincipal(AuthenticatedUser principal) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
        return auth.login(body.identifier(), body.password(), ip(req), userAgent(req));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest req) {
        return auth.refresh(body.refreshToken(), ip(req), userAgent(req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body) {
        auth.logout(body != null ? body.refreshToken() : null);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/account/username")
    public MeResponse setUsername(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @Valid @RequestBody SetUsernameRequest body) {
        requirePrincipal(principal);
        return account.setUsername(principal.userId(), body.username());
    }

    @PutMapping("/account/password")
    public MeResponse setPassword(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @Valid @RequestBody SetPasswordRequest body) {
        requirePrincipal(principal);
        return account.setPassword(principal.userId(), body.currentPassword(), body.newPassword());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Not authenticated");
        }
        return auth.me(principal.userId());
    }

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
