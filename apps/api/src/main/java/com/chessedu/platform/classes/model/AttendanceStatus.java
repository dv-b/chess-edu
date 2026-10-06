package com.chessedu.platform.classes.model;

/**
 * Marks how a student engaged with a given {@link ClassSession}.
 *
 * <p>The absence of a row for a (session, student) pair is <em>not</em>
 * equivalent to {@link #ABSENT}; it means "nobody has marked this student
 * yet". UI code should join against the class roster to distinguish the two.
 */
public enum AttendanceStatus {
    /** Student showed up on time. */
    PRESENT,
    /** Student did not show up. */
    ABSENT,
    /** Student showed up late. */
    LATE,
    /** Student was absent with a valid, school-accepted reason. */
    EXCUSED
}
