package com.chessedu.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for {@code chessedu.security.*} config. Keeps secrets and
 * token TTLs in one place so they're easy to audit and override per env.
 */
@ConfigurationProperties(prefix = "chessedu.security")
public record SecurityProperties(Jwt jwt) {

    public record Jwt(
            String issuer,
            String audience,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            String secret
    ) {
    }
}
