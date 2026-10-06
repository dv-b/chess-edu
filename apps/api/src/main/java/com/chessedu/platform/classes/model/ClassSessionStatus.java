package com.chessedu.platform.classes.model;

/**
 * Lifecycle of one scheduled meeting. "In progress" is derived from
 * {@code starts_at &lt;= now &lt; ends_at AND status = SCHEDULED}; no dedicated
 * enum value.
 */
public enum ClassSessionStatus {
    /** Planned; will happen (or already did, if we haven't swept it to COMPLETED). */
    SCHEDULED,
    /** Called off ahead of time; row is kept for history. */
    CANCELLED,
    /** Marked done — used once attendance/homework features land; set by a sweep job or the UI. */
    COMPLETED
}
