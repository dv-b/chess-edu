package com.chessedu.platform.identity.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending invitation. {@code schoolId} is null for PLATFORM_ADMIN invites;
 * non-null for every school-scoped role.
 */
@Entity
@Table(name = "invitation")
public class Invitation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "school_id")
    private UUID schoolId;

    @Column(name = "role", nullable = false, length = 24)
    private String role;     // Role OR "PLATFORM_ADMIN" — kept as String to allow the latter.

    @Column(name = "email", nullable = false, length = 240)
    private String email;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /**
     * E.164 phone the admin recorded for a Telegram invitation (V017). When
     * set, the bot can match the invitee by the contact they share — no link
     * to forward — and a token-based signup must share this same number.
     */
    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user")
    private UUID acceptedUser;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private JsonNode metadata;

    /** How this invite reaches the invitee. Defaults to EMAIL (the original path). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "delivery_channel", nullable = false, length = 16)
    private DeliveryChannel deliveryChannel = DeliveryChannel.EMAIL;

    /** Bot-funnel state for TELEGRAM invites; null for EMAIL. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "bot_state", length = 24)
    private BotState botState;

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSchoolId() { return schoolId; }
    public void setSchoolId(UUID schoolId) { this.schoolId = schoolId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public UUID getInvitedBy() { return invitedBy; }
    public void setInvitedBy(UUID invitedBy) { this.invitedBy = invitedBy; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }

    public UUID getAcceptedUser() { return acceptedUser; }
    public void setAcceptedUser(UUID acceptedUser) { this.acceptedUser = acceptedUser; }

    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode metadata) { this.metadata = metadata; }

    public DeliveryChannel getDeliveryChannel() { return deliveryChannel; }
    public void setDeliveryChannel(DeliveryChannel deliveryChannel) { this.deliveryChannel = deliveryChannel; }

    public BotState getBotState() { return botState; }
    public void setBotState(BotState botState) { this.botState = botState; }
}
