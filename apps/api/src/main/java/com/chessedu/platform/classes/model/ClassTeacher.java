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
 * Junction: teacher user ↔ class. Composite PK, one row per pair.
 */
@Entity
@Table(name = "class_teacher")
public class ClassTeacher {

    @EmbeddedId
    private ClassTeacherId id;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Embeddable
    public static class ClassTeacherId implements Serializable {
        @Column(name = "class_id", nullable = false)
        private UUID classId;

        @Column(name = "teacher_user_id", nullable = false)
        private UUID teacherUserId;

        public ClassTeacherId() {}
        public ClassTeacherId(UUID classId, UUID teacherUserId) {
            this.classId = classId;
            this.teacherUserId = teacherUserId;
        }

        public UUID getClassId() { return classId; }
        public UUID getTeacherUserId() { return teacherUserId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ClassTeacherId that)) return false;
            return Objects.equals(classId, that.classId)
                    && Objects.equals(teacherUserId, that.teacherUserId);
        }
        @Override public int hashCode() { return Objects.hash(classId, teacherUserId); }
    }

    // ------------------------------------------------------------------------

    public ClassTeacherId getId() { return id; }
    public void setId(ClassTeacherId id) { this.id = id; }

    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
}
