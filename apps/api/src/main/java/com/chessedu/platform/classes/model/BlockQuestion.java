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
 * One block-level Q&amp;A thread head.
 *
 * <p>A student opens a thread anchored to a specific block in a homework
 * or lesson; teachers (and the asker) reply via {@link BlockQuestionReply}.
 * The thread's identity is the row itself — service code reconstructs
 * the conversation by joining {@code block_question_reply} on
 * {@code question_id}.
 *
 * <p>{@code parentKind} + {@code parentId} carry the polymorphic
 * reference to either a {@link Homework} or a {@link Lesson}; the
 * service authorises against the matching parent at write time. See
 * {@link BlockQuestionParentKind} for why the table isn't split.
 *
 * <p>Resolution semantics: an "open" thread has both
 * {@code resolvedAt} and {@code resolvedBy} null; "closed" has both
 * non-null. The CHECK constraint in the schema enforces the pairing.
 *
 * <p>Soft-delete: questions are NOT archived. If a parent is archived,
 * the inbox roll-up filters in service code based on the parent's
 * {@code archived_at}; the question row itself stays as a historical
 * record.
 */
@Entity
@Table(name = "block_question")
@EntityListeners(AuditingEntityListener.class)
public class BlockQuestion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Denormalised so the inbox query doesn't have to join to the parent. */
    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "parent_kind", nullable = false, length = 16)
    private BlockQuestionParentKind parentKind;

    @Column(name = "parent_id", nullable = false)
    private UUID parentId;

    @Column(name = "block_index", nullable = false)
    private int blockIndex;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    @Column(name = "body", nullable = false)
    private String body;

    /** Non-null iff the thread is closed; paired with {@code resolvedBy}. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** User who closed the thread (teacher or the asker). */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

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

    public BlockQuestionParentKind getParentKind() { return parentKind; }
    public void setParentKind(BlockQuestionParentKind parentKind) { this.parentKind = parentKind; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public int getBlockIndex() { return blockIndex; }
    public void setBlockIndex(int blockIndex) { this.blockIndex = blockIndex; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
