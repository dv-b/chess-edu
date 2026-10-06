package com.chessedu.platform.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Wraps the {@link PasswordEncoder} bean so domain code doesn't import
 * Spring Security types. Argon2id under the hood (see SecurityConfig).
 */
@Service
public class PasswordService {

    private final PasswordEncoder encoder;

    public PasswordService(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String hash) {
        if (rawPassword == null || hash == null) {
            return false;
        }
        return encoder.matches(rawPassword, hash);
    }

    public boolean needsRehash(String hash) {
        // Spring Security delegates this to the encoder; Argon2PasswordEncoder
        // returns true when parameters drift from current defaults.
        return encoder.upgradeEncoding(hash);
    }
}
