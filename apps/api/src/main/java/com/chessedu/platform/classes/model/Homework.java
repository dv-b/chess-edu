package com.chessedu.platform.classes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One homework assignment given to a class.
 *
 * <p>A {@code Homework} belongs to exactly one {@link ClassGroup} and may
 * optionally point at a specific {@link ClassSession} (the session where it
 * was handed out). Per-student state lives in {@link HomeworkSubmission}
 * and is keyed by (homework_id, student_user_id).
 *
 * <p>Soft-deleted via {@code archivedAt}: the admin UI hides archived rows
 * from its "Active" list but the submission trail survives so that
 * historical parent/admin reports continue to make sense.
 */
@Entity
@Table(name = "homework")
@EntityListeners(AuditingEntityListener.class)
public class Homework {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    /** Optional — the class_session where this homework was assigned. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "instructions")
    private String instructions;

    @Column(name = "due_at")
    private Instant dueAt;

    /** User who created the assignment. Null if the originator was archived. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** Non-null means the assignment has been archived (soft-deleted). */
    @Column(name = "archived_at")
    private Instant archivedAt;

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

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
