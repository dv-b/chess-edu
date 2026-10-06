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
 * A single authored content block attached to a {@link Lesson}. Four
 * {@code kind}s are supported in v1 — PROSE, BOARD_WALKTHROUGH, MCQ, VIDEO
 * — but the column is an open-ended VARCHAR so we can add AUDIO /
 * PUZZLE_SET / etc. without a migration.
 *
 * <p>Expected payload shapes (validated at the service layer):
 * <pre>{@code
 * PROSE              { "markdown": "..." }
 *
 * BOARD_WALKTHROUGH  {
 *   "startingFen":  "<FEN>",
 *   "moves":        ["e2e4", "e7e5", ...],
 *   "orientation":  "white" | "black",
 *   "annotations":  [{"ply": 0, "text": "..."}, ...]  // optional
 * }
 *
 * MCQ                {
 *   "question":     "...",
 *   "options":      ["A", "B", "C", "D"],  // 2..4
 *   "correctIndex": 0,
 *   "explanation":  "..."                   // optional
 * }
 *
 * VIDEO              {
 *   "url":      "https://www.youtube.com/watch?v=...",
 *   "provider": "youtube" | "vimeo"
 * }
 * }</pre>
 */
@Entity
@Table(name = "lesson_resource")
@EntityListeners(AuditingEntityListener.class)
public class LessonResource {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;

    /**
     * Ordinal of this block inside its lesson, starting at 0. Unique per
     * lesson — the service recomputes positions on every list replace, so
     * callers never have to worry about collisions.
     */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "kind", nullable = false, length = 32)
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

    public UUID getLessonId() { return lessonId; }
    public void setLessonId(UUID lessonId) { this.lessonId = lessonId; }

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
