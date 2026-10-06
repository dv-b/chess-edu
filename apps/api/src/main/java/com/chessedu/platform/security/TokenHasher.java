package com.chessedu.platform.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Hashes opaque tokens (refresh, password-reset, invitation) with SHA-256
 * before they touch the database. Also mints new tokens.
 *
 * <p>Design: we use a plain hash (not HMAC) because the tokens themselves
 * are 256 bits of CSPRNG output — pre-image resistance is what we care about,
 * and brute-forcing a 256-bit random is not a thing. Tokens are transmitted
 * base64url so they're URL-safe and stored as lowercase hex.
 */
@Component
public class TokenHasher {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    /** 32 bytes = 256 bits — same entropy as a UUIDv4 with room to spare. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Mints a fresh opaque token. Returns the plaintext value the caller
     * should ship to the client; store {@link #hash(String)} of it server-side.
     */
    public String mint() {
        byte[] raw = new byte[TOKEN_BYTES];
        RNG.nextBytes(raw);
        return BASE64URL.encodeToString(raw);
    }

    public String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is guaranteed by the JDK — unreachable.
            throw new IllegalStateException(ex);
        }
    }
}
