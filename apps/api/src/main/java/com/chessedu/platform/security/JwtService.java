package com.chessedu.platform.security;

import com.chessedu.platform.common.error.AppException;
import com.chessedu.platform.common.error.ErrorCode;
import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies access-token JWTs (HS256 for MVP).
 *
 * <p>Access tokens are short-lived (30 min) and carry just enough to
 * authorise a request without a DB round-trip:
 * <ul>
 *   <li>{@code sub}     — user id</li>
 *   <li>{@code sid}     — school id, null for platform staff</li>
 *   <li>{@code role}    — role inside the school (null for platform staff)</li>
 *   <li>{@code prole}   — platform role (null for school users)</li>
 *   <li>{@code email}   — user email (for logs/display only; never trusted for authz)</li>
 * </ul>
 *
 * <p>We'll move to RSA/EdDSA when there's a second relying party.
 */
@Service
public class JwtService {

    private static final String CLAIM_SCHOOL = "sid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PLATFORM_ROLE = "prole";
    private static final String CLAIM_EMAIL = "email";

    private final SecurityProperties props;
    private final JWSSigner signer;
    private final JWSVerifier verifier;

    public JwtService(SecurityProperties props) {
        this.props = props;
        try {
            byte[] key = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) {
                throw new IllegalStateException(
                        "chessedu.security.jwt.secret must be at least 32 bytes for HS256");
            }
            this.signer = new MACSigner(key);
            this.verifier = new MACVerifier(key);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to init JWT signer", ex);
        }
    }

    public String issueAccessToken(AccessTokenClaims claims) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.jwt().accessTokenTtl());

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(claims.userId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issuer(props.jwt().issuer())
                .audience(props.jwt().audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim(CLAIM_EMAIL, claims.email());

        if (claims.schoolId() != null) {
            builder.claim(CLAIM_SCHOOL, claims.schoolId().toString());
        }
        if (claims.role() != null) {
            builder.claim(CLAIM_ROLE, claims.role().name());
        }
        if (claims.platformRole() != null) {
            builder.claim(CLAIM_PLATFORM_ROLE, claims.platformRole().name());
        }

        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    public AccessTokenClaims verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                throw new AppException(ErrorCode.UNAUTHENTICATED, "Invalid token signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) {
                throw new AppException(ErrorCode.UNAUTHENTICATED, "Token expired");
            }
            if (!props.jwt().issuer().equals(claims.getIssuer())) {
                throw new AppException(ErrorCode.UNAUTHENTICATED, "Token issuer mismatch");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(props.jwt().audience())) {
                throw new AppException(ErrorCode.UNAUTHENTICATED, "Token audience mismatch");
            }

            UUID userId = UUID.fromString(claims.getSubject());
            UUID schoolId = Optional.ofNullable(claims.getStringClaim(CLAIM_SCHOOL))
                    .map(UUID::fromString)
                    .orElse(null);
            Role role = Optional.ofNullable(claims.getStringClaim(CLAIM_ROLE))
                    .map(Role::valueOf)
                    .orElse(null);
            PlatformRole platformRole = Optional.ofNullable(claims.getStringClaim(CLAIM_PLATFORM_ROLE))
                    .map(PlatformRole::valueOf)
                    .orElse(null);
            String email = claims.getStringClaim(CLAIM_EMAIL);

            return new AccessTokenClaims(userId, email, schoolId, role, platformRole);
        } catch (ParseException | JOSEException | IllegalArgumentException ex) {
            throw new AppException(ErrorCode.UNAUTHENTICATED, "Invalid token");
        }
    }

    public record AccessTokenClaims(
            UUID userId,
            String email,
            UUID schoolId,
            Role role,
            PlatformRole platformRole
    ) {
    }
}
