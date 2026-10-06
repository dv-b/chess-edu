package com.chessedu.platform.security;

import com.chessedu.platform.common.error.ApiError;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Real security wiring.
 *
 * <p>Stateless sessions, JWT bearer auth via {@link JwtAuthenticationFilter},
 * CORS scoped to the web app origin. Unauthorised / forbidden responses are
 * shaped as {@link ApiError} so clients parse one shape for every error.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // --- Public ---
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/auth/login",
                                         "/api/v1/auth/refresh",
                                         "/api/v1/auth/logout",
                                         "/api/v1/auth/password-reset/**",
                                         "/api/v1/auth/invitations/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ping").permitAll()
                        // --- Authenticated + role-gated ---
                        // Teachers need to see the schedule (patterns + sessions)
                        // of classes they're attached to, even though the URL lives
                        // under /admin for consistency with the rest of the class
                        // surface. The method-level @PreAuthorize narrows further.
                        .requestMatchers(HttpMethod.GET,
                                         "/api/v1/admin/classes/*/patterns",
                                         "/api/v1/admin/classes/*/sessions")
                                .hasAnyRole("SCHOOL_ADMIN", "PLATFORM_ADMIN", "TEACHER")
                        // Teachers mark attendance and manage homework for the
                        // classes they're assigned to. The filter lets TEACHER
                        // through; the service layer enforces the tighter
                        // "must be on this class's teacher roster" check.
                        .requestMatchers("/api/v1/admin/classes/*/sessions/*/attendance",
                                         "/api/v1/admin/classes/*/homework",
                                         "/api/v1/admin/classes/*/homework/**")
                                .hasAnyRole("SCHOOL_ADMIN", "PLATFORM_ADMIN", "TEACHER")
                        .requestMatchers("/api/v1/admin/**")
                                .hasAnyRole("SCHOOL_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll()
                )
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, req, objectMapper,
                                HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, req, objectMapper,
                                HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Argon2id — OWASP recommendation.
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", config);
        return src;
    }

    private static void writeError(HttpServletResponse res, HttpServletRequest req, ObjectMapper mapper,
                                    int status, ErrorCode code) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object requestId = req.getAttribute(RequestIdFilter.ATTR_REQUEST_ID);
        ApiError body = ApiError.of(code, req.getRequestURI(), requestId != null ? requestId.toString() : null);
        mapper.writeValue(res.getOutputStream(), body);
    }
}
