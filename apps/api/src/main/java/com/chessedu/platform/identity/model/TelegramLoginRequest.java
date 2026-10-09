package com.chessedu.platform.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending "log in with Telegram" handshake started by a browser (see
 * V013). Lifecycle: created (pending) → completed by the bot for a user →
 * consumed by the browser that created it (session issued). Any step can
 * instead run into {@code expiresAt}.
 *
 * <p>Only hashes of the nonce and the client secret are stored; the
 * plaintexts live in the deeplink and the browser cookie respectively. No
 * {@code @GeneratedValue} — the service assigns the UUID, matching the other
 * token tables in this domain.
 */
@Entity
@Table(name = "telegram_login_request")
public class TelegramLoginRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "nonce_hash", nullable = false, unique = true, length = 128)
    private String nonceHash;

    @Column(name = "client_secret_hash", nullable = false, length = 128)
    private String clientSecretHash;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isCompleted() { return completedAt != null; }
    public boolean isConsumed() { return consumedAt != null; }

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getNonceHash() { return nonceHash; }
    public void setNonceHash(String nonceHash) { this.nonceHash = nonceHash; }

    public String getClientSecretHash() { return clientSecretHash; }
    public void setClientSecretHash(String clientSecretHash) { this.clientSecretHash = clientSecretHash; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
}
