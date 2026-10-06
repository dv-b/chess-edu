package com.chessedu.platform.classes.model;

import com.fasterxml.jackson.databind.JsonNode;
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
 * A single submission attempt for one block of one homework by one student —
 * the new work unit introduced by Task #28 (see
 * {@code docs/design/student-solution-moves.md} and
 * {@code V008__homework_block_attempt.sql}).
 *
 * <p>Attempts are <b>history-preserving</b>. When a student resubmits (the
 * "Overwrite &amp; submit" flow in the mocks), the old row's
 * {@link #replacedById} is stamped with the new row's id in the same
 * transaction. The partial unique index in V008 guarantees at most one
 * <em>current</em> (unreplaced) row per (homework, student, block), while
 * still permitting the full chain of prior attempts to co-exist for audit.
 *
 * <p>Relationship to {@link HomeworkSubmission}: the older per-assignment
 * DONE/SKIPPED self-mark survives unchanged. The per-block state that the
 * student and teacher actually interact with lives here. Absence of any
 * attempt row for (homework, student, block) is the "Not attempted" UI state.
 *
 * <p>Payload shape for {@link #moves} (JSONB, always an array):
 * <pre>{@code ["e2e4", "e7e5", "g1f3", ...]}</pre>
 * Each entry is a lowercase UCI move. The validator in the service layer
 * enforces legality against the block's starting FEN before persisting.
 *
 * <p>Review fields ({@link #reviewedBy}, {@link #reviewedAt}) are set together
 * — the check constraint {@code homework_block_attempt_review_pair_chk}
 * rejects any row that carries one without the other.
 *
 * <p>{@link #solutionRevealedAt} is the "Solution revealed" pill from the
 * mocks. It only makes sense after a review; the DB check constraint guards
 * the timestamp pair, and the service layer further restricts reveal to the
 * {@link BlockAttemptStatus#REVIEWED_NEEDS_WORK} path (a correct attempt has
 * no reason to unlock the canonical answer).
 */
@Entity
@Table(name = "homework_block_attempt")
@EntityListeners(AuditingEntityListener.class)
public class HomeworkBlockAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "homework_id", nullable = false)
    private UUID homeworkId;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    /**
     * Ordinal of the block this attempt targets, mirroring
     * {@link HomeworkResource#getPosition()}. Zero-based.
     */
    @Column(name = "block_index", nullable = false)
    private int blockIndex;

    /**
     * JSONB array of UCI move strings. Defaults to an empty array at the DB
     * level ({@code '[]'::jsonb}) but the service path always writes what the
     * student submitted — validated to be a JSON array of UCI strings.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "moves", nullable = false, columnDefinition = "jsonb")
    private JsonNode moves;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private BlockAttemptStatus status;

    /** Freeform teacher note. No markdown rendering server-side in v1. */
    @Column(name = "teacher_note")
    private String teacherNote;

    /** Nullable until the teacher reviews; set together with {@link #reviewedAt}. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /** Nullable until the teacher reviews; set together with {@link #reviewedBy}. */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * Timestamp of the student unlocking the recorded solution. Separate from
     * {@link #status} so the teacher's Needs-work assessment is preserved
     * after the reveal.
     */
    @Column(name = "solution_revealed_at")
    private Instant solutionRevealedAt;

    /**
     * Self-reference to the attempt that superseded this one. NULL for the
     * current attempt in the chain; set on every prior attempt once the
     * student overwrites. The partial unique index
     * {@code homework_block_attempt_current_idx} in V008 keys off this column.
     */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getHomeworkId() { return homeworkId; }
    public void setHomeworkId(UUID homeworkId) { this.homeworkId = homeworkId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public int getBlockIndex() { return blockIndex; }
    public void setBlockIndex(int blockIndex) { this.blockIndex = blockIndex; }

    public JsonNode getMoves() { return moves; }
    public void setMoves(JsonNode moves) { this.moves = moves; }

    public BlockAttemptStatus getStatus() { return status; }
    public void setStatus(BlockAttemptStatus status) { this.status = status; }

    public String getTeacherNote() { return teacherNote; }
    public void setTeacherNote(String teacherNote) { this.teacherNote = teacherNote; }

    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public Instant getSolutionRevealedAt() { return solutionRevealedAt; }
    public void setSolutionRevealedAt(Instant solutionRevealedAt) { this.solutionRevealedAt = solutionRevealedAt; }

    public UUID getReplacedById() { return replacedById; }
    public void setReplacedById(UUID replacedById) { this.replacedById = replacedById; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
