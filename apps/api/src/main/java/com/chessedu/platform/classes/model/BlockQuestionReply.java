package com.chessedu.platform.classes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One reply on a {@link BlockQuestion} thread.
 *
 * <p>Replies are immutable in v1 — no edit / delete affordance from
 * the UI. If we add edit later it'll be a soft "edited at" timestamp +
 * version row, not destructive. Until then, only {@code createdAt} is
 * tracked (no {@code updatedAt}, deliberately).
 *
 * <p>The author can be either the asking student (following up) or a
 * teacher / school admin responding. We don't denormalise the role
 * because it can change over time; the service derives it on read by
 * looking at {@code app_user.role} at the time of rendering. That
 * means a former student who's now a teacher will retroactively appear
 * as a teacher in old threads — acceptable trade-off for a small data
 * model. If teachers ever ask "who actually said what at the time?",
 * we'd need a snapshotted role column.
 */
@Entity
@Table(name = "block_question_reply")
@EntityListeners(AuditingEntityListener.class)
public class BlockQuestionReply {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    @Column(name = "body", nullable = false)
    private String body;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getQuestionId() { return questionId; }
    public void setQuestionId(UUID questionId) { this.questionId = questionId; }

    public UUID getAuthorUserId() { return authorUserId; }
    public void setAuthorUserId(UUID authorUserId) { this.authorUserId = authorUserId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
