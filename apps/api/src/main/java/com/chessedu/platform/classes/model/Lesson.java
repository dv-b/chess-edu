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
 * One lesson (study material page) published to a class.
 *
 * <p>Lessons are the "study" side of the curriculum, parallel to
 * {@link Homework} (the "solve" side). Unlike homework, lessons have no
 * {@code dueAt} — study material is revisitable, not due-datable.
 * Per-student completion lives in {@link LessonCompletion} and is keyed
 * by (lesson_id, student_user_id).
 *
 * <p>Soft-deleted via {@code archivedAt}: the admin UI hides archived
 * rows from the active list but the read-trail survives so historical
 * reports ("did this student cover opening principles?") continue to
 * make sense.
 */
@Entity
@Table(name = "lesson")
@EntityListeners(AuditingEntityListener.class)
public class Lesson {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    /** Optional — the class_session where this lesson was covered. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "instructions")
    private String instructions;

    /** User who created the lesson. Null if the originator was archived. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** Non-null means the lesson has been archived (soft-deleted). */
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

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
