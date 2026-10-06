package com.chessedu.platform.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 1:1 link between an {@link User} and a Telegram identity.
 *
 * <p>Kept in its own table (rather than columns on {@code app_user}) because
 * most users never link Telegram, and the Telegram-specific profile snapshot
 * (username, photo, language) doesn't belong on the core identity row. Mirrors
 * the "credentials in their own table" decision from V002.
 *
 * <p>Timestamps are domain-named ({@code linkedAt} / {@code lastSeenAt}) and
 * set explicitly by the service, matching the {@link RefreshToken} /
 * {@link PasswordResetToken} convention in this identity domain rather than the
 * {@code @CreatedDate}/{@code @LastModifiedDate} auditing listener used by
 * {@link User}. No {@code @GeneratedValue} — the service assigns the UUID.
 */
@Entity
@Table(name = "telegram_account")
public class TelegramAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /** Stable Telegram-side primary key — never changes; all lookups key on this. */
    @Column(name = "telegram_id", nullable = false, unique = true)
    private long telegramId;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Column(name = "last_name", length = 128)
    private String lastName;

    @Column(name = "photo_url", length = 512)
    private String photoUrl;

    /** E.164, Telegram-verified via the "Share Contact" button. */
    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "language_code", length = 16)
    private String languageCode;

    /** Private-chat id the bot DMs this user on. */
    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public long getTelegramId() { return telegramId; }
    public void setTelegramId(long telegramId) { this.telegramId = telegramId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }

    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }

    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
