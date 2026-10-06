package com.chessedu.platform.classes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A weekly recurring rule attached to a class, e.g. "every Monday from
 * 16:00 to 17:00 in Asia/Tashkent, starting 2026-04-01, no end date".
 *
 * <p>The pattern is a template — it does not represent a meeting itself.
 * {@link ClassSession} rows are <em>materialised</em> from each pattern,
 * up to a horizon defined by the service layer.
 *
 * <p>Day of week is stored as ISO-8601 ordinal (1=Mon..7=Sun) so it lines
 * up trivially with {@link DayOfWeek#getValue()}.
 */
@Entity
@Table(name = "class_schedule_pattern")
@EntityListeners(AuditingEntityListener.class)
public class ClassSchedulePattern {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    /** ISO-8601 day-of-week: 1=Mon..7=Sun. Mirrors {@link DayOfWeek#getValue()}. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    /** Inclusive last date; null = open-ended. */
    @Column(name = "ends_on")
    private LocalDate endsOn;

    /** IANA timezone id, e.g. {@code Asia/Tashkent}. */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Asia/Tashkent";

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

    public DayOfWeek getDayOfWeek() { return DayOfWeek.of(dayOfWeek); }
    public void setDayOfWeek(DayOfWeek dow) { this.dayOfWeek = (short) dow.getValue(); }
    public short getDayOfWeekRaw() { return dayOfWeek; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public LocalDate getStartsOn() { return startsOn; }
    public void setStartsOn(LocalDate startsOn) { this.startsOn = startsOn; }

    public LocalDate getEndsOn() { return endsOn; }
    public void setEndsOn(LocalDate endsOn) { this.endsOn = endsOn; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
