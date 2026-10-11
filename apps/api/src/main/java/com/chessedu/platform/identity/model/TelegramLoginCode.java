package com.chessedu.platform.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A 6-digit sign-in code the bot sent to a linked Telegram account (V015).
 * Only the code's SHA-256 is stored; the plaintext exists in one Telegram
 * message. Dies on consumption, expiry, or too many wrong attempts.
 *
 * <p>A row with a null {@code userId} is a decoy minted for an identifier that
 * matched no Telegram-linked account: it behaves exactly like a real one
 * (TTL, attempt counting, 401 / 410) but can never be verified, so the
 * endpoints can't be used to probe which accounts exist.
 */
@Entity
@Table(name = "telegram_login_code")
public class TelegramLoginCode {

    public static final int MAX_ATTEMPTS = 5;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null on a decoy row (unmatched identifier) — such a row never verifies. See V016. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExhausted() { return attempts >= MAX_ATTEMPTS; }
    public boolean isUsable(Instant now) { return !isConsumed() && !isExpired(now) && !isExhausted(); }

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
}
