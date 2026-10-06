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
 * One student's completion state on one {@link Lesson}.
 *
 * <p>Unique on (lesson_id, student_user_id). A missing row means
 * "not yet started" — we only insert a row when the student actively
 * records a state (including explicit NOT_STARTED via a reset).
 *
 * <p>Lighter than {@link HomeworkSubmission}: no {@code submittedAt}, no
 * {@code markedBy} (students can only mark their own lessons read — no
 * teacher-on-behalf flow in v1), no student note.
 */
@Entity
@Table(name = "lesson_completion")
@EntityListeners(AuditingEntityListener.class)
public class LessonCompletion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private LessonCompletionStatus status;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt = Instant.now();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getLessonId() { return lessonId; }
    public void setLessonId(UUID lessonId) { this.lessonId = lessonId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public LessonCompletionStatus getStatus() { return status; }
    public void setStatus(LessonCompletionStatus status) { this.status = status; }

    public Instant getMarkedAt() { return markedAt; }
    public void setMarkedAt(Instant markedAt) { this.markedAt = markedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
