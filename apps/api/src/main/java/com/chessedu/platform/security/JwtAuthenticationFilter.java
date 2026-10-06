package com.chessedu.platform.security;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Parses {@code Authorization: Bearer <jwt>} on every request, populates
 * {@link SecurityContextHolder} and {@link TenantContext}, and always
 * cleans up on the way out.
 *
 * <p>If the header is missing or invalid the filter does nothing — downstream
 * authorization rules decide whether the endpoint requires auth. Endpoints
 * marked {@code permitAll()} (login, refresh, actuator health) still work.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = extract(req);
        boolean tenantSet = false;

        if (token != null) {
            try {
                JwtService.AccessTokenClaims claims = jwtService.verify(token);
                AuthenticatedUser principal = new AuthenticatedUser(
                        claims.userId(),
                        claims.email(),
                        claims.schoolId(),
                        claims.role(),
                        claims.platformRole()
                );
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.authorities());
                SecurityContextHolder.getContext().setAuthentication(auth);

                if (claims.schoolId() != null) {
                    TenantContext.set(claims.schoolId());
                    tenantSet = true;
                }
            } catch (AppException ex) {
                // Invalid/expired — leave context empty; GlobalExceptionHandler
                // will 401 anything that needed auth.
                log.debug("JWT rejected: {}", ex.getMessage());
            }
        }

        try {
            chain.doFilter(req, res);
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
            SecurityContextHolder.clearContext();
        }
    }

    private static String extract(HttpServletRequest req) {
        String header = req.getHeader(HEADER);
        if (!StringUtils.hasText(header) || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
