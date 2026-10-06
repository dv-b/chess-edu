package com.chessedu.platform.classes.model;

/**
 * Per-student submission state for a single {@link Homework}.
 *
 * <p>The absence of a row in {@code homework_submission} means the student
 * has not interacted with the assignment yet. That is semantically identical
 * to {@link #NOT_STARTED}, but we keep the enum value so that a student who
 * opened-and-reset an assignment can be told apart from a brand-new student
 * added to the class after the homework was issued.
 *
 * <ul>
 *   <li>{@link #NOT_STARTED} — the student has acknowledged the assignment
 *       but not finished it. Writing this explicitly (rather than just
 *       leaving the row absent) lets the student press a "reset" button and
 *       still see the assignment in their queue.</li>
 *   <li>{@link #DONE} — the student (or the teacher on their behalf) has
 *       marked it as completed. {@code submitted_at} is set at this point.</li>
 *   <li>{@link #SKIPPED} — the student or teacher explicitly chose to skip
 *       this assignment. Rollups treat this as "not done" but not as a
 *       pending nag in the UI.</li>
 * </ul>
 */
public enum HomeworkSubmissionStatus {
    NOT_STARTED,
    DONE,
    SKIPPED
}
