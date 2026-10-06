package com.chessedu.platform.classes.model;

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
 * Junction: student user ↔ class (enrolment). Composite PK, one row per pair.
 */
@Entity
@Table(name = "class_student")
public class ClassStudent {

    @EmbeddedId
    private ClassStudentId id;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt = Instant.now();

    @Embeddable
    public static class ClassStudentId implements Serializable {
        @Column(name = "class_id", nullable = false)
        private UUID classId;

        @Column(name = "student_user_id", nullable = false)
        private UUID studentUserId;

        public ClassStudentId() {}
        public ClassStudentId(UUID classId, UUID studentUserId) {
            this.classId = classId;
            this.studentUserId = studentUserId;
        }

        public UUID getClassId() { return classId; }
        public UUID getStudentUserId() { return studentUserId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClassStudentId that)) return false;
            return Objects.equals(classId, that.classId)
                    && Objects.equals(studentUserId, that.studentUserId);
        }
        @Override public int hashCode() { return Objects.hash(classId, studentUserId); }
    }

    // ------------------------------------------------------------------------

    public ClassStudentId getId() { return id; }
    public void setId(ClassStudentId id) { this.id = id; }

    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant enrolledAt) { this.enrolledAt = enrolledAt; }
}
