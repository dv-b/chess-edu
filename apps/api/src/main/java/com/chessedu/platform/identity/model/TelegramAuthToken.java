package com.chessedu.platform.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One-time token for the bot→web (and Login-Widget→web) handoff. After the bot
 * creates / identifies a user it mints one of these and hands the plaintext to
 * the user inside an {@code /auth/accept?ott=<token>} deeplink; the web app POSTs
 * the plaintext back to redeem it for a JWT pair.
 *
 * <p>Shape mirrors {@link PasswordResetToken} line-for-line: only a SHA-256 hex
 * hash is stored (the plaintext lives solely in the deeplink), single-use
 * ({@code usedAt} stamps on redemption), short TTL. No {@code @GeneratedValue} —
 * the service assigns the UUID and sets {@code issuedAt} explicitly.
 */
@Entity
@Table(name = "telegram_auth_token")
public class TelegramAuthToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isUsable(Instant now) {
        return !isUsed() && !isExpired(now);
    }

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
}
