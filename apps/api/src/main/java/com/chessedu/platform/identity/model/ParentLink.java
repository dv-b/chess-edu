package com.chessedu.platform.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Links a parent user to a student user, both inside the same school.
 * Composite PK (parentUserId, studentUserId) — many-to-many, one row per pair.
 */
@Entity
@Table(name = "parent_link")
public class ParentLink {

    @EmbeddedId
    private ParentLinkId id;

    @Column(name = "school_id", nullable = false)
    private UUID schoolId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Embeddable
    public static class ParentLinkId implements Serializable {
        @Column(name = "parent_user_id", nullable = false)
        private UUID parentUserId;

        @Column(name = "student_user_id", nullable = false)
        private UUID studentUserId;

        public ParentLinkId() {}
        public ParentLinkId(UUID parentUserId, UUID studentUserId) {
            this.parentUserId = parentUserId;
            this.studentUserId = studentUserId;
        }

        public UUID getParentUserId() { return parentUserId; }
        public UUID getStudentUserId() { return studentUserId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ParentLinkId that)) return false;
            return Objects.equals(parentUserId, that.parentUserId)
                    && Objects.equals(studentUserId, that.studentUserId);
        }
        @Override public int hashCode() { return Objects.hash(parentUserId, studentUserId); }
    }

    // ------------------------------------------------------------------------

    public ParentLinkId getId() { return id; }
    public void setId(ParentLinkId id) { this.id = id; }

    public UUID getSchoolId() { return schoolId; }
    public void setSchoolId(UUID schoolId) { this.schoolId = schoolId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
