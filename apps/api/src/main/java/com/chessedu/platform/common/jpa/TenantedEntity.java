package com.chessedu.platform.common.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapped superclass for every entity that belongs to a tenant.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code schoolId} is never set by client input — it comes from
 *       {@code TenantContext} at persist time.</li>
 *   <li>Once set, {@code schoolId} is immutable ({@code updatable = false}).</li>
 *   <li>Hibernate applies a {@code @Filter} in production so queries implicitly
 *       include {@code WHERE school_id = :tenantId}.</li>
 * </ul>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class TenantedEntity {

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(UUID schoolId) {
        this.schoolId = schoolId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
