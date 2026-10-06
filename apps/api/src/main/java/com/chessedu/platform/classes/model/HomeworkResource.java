package com.chessedu.platform.classes.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * A single authored content block attached to a {@link Homework}. In v1 the
 * only {@code kind} we produce and consume is {@code "BOARD"} (a chess
 * position with optional moves and solution), but the schema is intentionally
 * open-ended so we can add {@code QUIZ}, {@code VIDEO}, etc. without a
 * migration.
 *
 * <p>The {@code payload} shape is per-kind. For {@code BOARD}:
 * <pre>{@code
 * {
 *   "startingFen": "rnbqkbnr/...",
 *   "moves":       ["e2e4", "e7e5"],          // UCI, possibly empty
 *   "orientation": "white" | "black",
 *   "solution":    { "uci": ["g4e5", ...] }    // optional
 * }
 * }</pre>
 *
 * <p>Validation of the payload (legality of the FEN, legality of each move)
 * lives in the service layer — the entity is a pure persistence carrier.
 */
@Entity
@Table(name = "homework_resource")
@EntityListeners(AuditingEntityListener.class)
public class HomeworkResource {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "homework_id", nullable = false)
    private UUID homeworkId;

    /**
     * Ordinal of this block inside its homework, starting at 0. Unique per
     * homework — the service recomputes positions on every list replace, so
     * callers never have to worry about collisions.
     */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "title", length = 200)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

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

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
