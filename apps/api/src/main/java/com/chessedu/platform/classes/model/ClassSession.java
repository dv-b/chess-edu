package com.chessedu.platform.classes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One concrete meeting of a class at a specific UTC instant span.
 *
 * <p>Each session optionally links back to the {@link ClassSchedulePattern}
 * that generated it. Ad-hoc sessions carry {@code pattern_id = NULL}. When
 * a pattern is deleted, its sessions detach (FK is ON DELETE SET NULL)
 * rather than disappearing — we keep the history.
 *
 * <p>Times are stored as UTC instants. Display in the user's locale is the
 * UI's responsibility. The pattern's IANA timezone is only consulted at
 * materialisation time.
 */
@Entity
@Table(name = "class_session")
@EntityListeners(AuditingEntityListener.class)
public class ClassSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    /** Null for ad-hoc one-off sessions. */
    @Column(name = "pattern_id")
    private UUID patternId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private ClassSessionStatus status = ClassSessionStatus.SCHEDULED;

    @Column(name = "notes")
    private String notes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getClassId() { return classId; }
    public void setClassId(UUID classId) { this.classId = classId; }

    public UUID getPatternId() { return patternId; }
    public void setPatternId(UUID patternId) { this.patternId = patternId; }

    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }

    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }

    public ClassSessionStatus getStatus() { return status; }
    public void setStatus(ClassSessionStatus status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
