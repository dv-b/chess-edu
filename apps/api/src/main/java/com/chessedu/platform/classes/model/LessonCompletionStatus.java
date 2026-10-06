package com.chessedu.platform.classes.model;

/**
 * Per-student completion state for a single {@link Lesson}.
 *
 * <p>As with {@link HomeworkSubmissionStatus}, the absence of a row in
 * {@code lesson_completion} means the student hasn't interacted with the
 * lesson yet. That is semantically identical to {@link #NOT_STARTED}, but
 * we keep the enum value so a student who opened-and-reset a lesson can
 * be told apart from one who's never clicked into it — handy for the
 * "started but didn't finish" slice of the roll-up.
 *
 * <ul>
 *   <li>{@link #NOT_STARTED} — the student has opened the lesson but not
 *       marked it read (or has explicitly reset). Writing this explicitly
 *       lets a student clear a previous READ without erasing the history
 *       of having touched the lesson.</li>
 *   <li>{@link #READ} — the student has marked the lesson as read.
 *       {@code markedAt} is updated at this point.</li>
 * </ul>
 */
public enum LessonCompletionStatus {
    NOT_STARTED,
    READ
}
