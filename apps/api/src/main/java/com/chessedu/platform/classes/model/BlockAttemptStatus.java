package com.chessedu.platform.classes.model;

/**
 * Teacher-visible outcome of a single {@link HomeworkBlockAttempt}.
 *
 * <p>This is the persisted slice of the block-level lifecycle described in
 * {@code docs/design/student-solution-moves.md}. The full UI state machine has
 * six states, but four of them are DERIVED at read-time rather than stored:
 *
 * <ul>
 *   <li><b>Not attempted</b> — no row exists for (homework, student, block).</li>
 *   <li><b>In progress</b> — purely client-side in v1 (no autosave).</li>
 *   <li><b>Submitted</b> — persisted as {@link #SUBMITTED}.</li>
 *   <li><b>Reviewed — OK</b> — persisted as {@link #REVIEWED_OK}.</li>
 *   <li><b>Reviewed — Needs work</b> — persisted as
 *       {@link #REVIEWED_NEEDS_WORK}.</li>
 *   <li><b>Solution revealed</b> — derived from
 *       {@link #REVIEWED_NEEDS_WORK} <em>plus</em> a non-null
 *       {@code solution_revealed_at} timestamp on the same row. Keeping reveal
 *       separate from the enum preserves the teacher's assessment after the
 *       student unlocks the canonical answer.</li>
 * </ul>
 *
 * <p>The whitelist of names here is duplicated in the
 * {@code homework_block_attempt_status_chk} check constraint in
 * {@code V008__homework_block_attempt.sql}. Keep the two in sync by convention
 * — Flyway won't guard you. Same arrangement we use for
 * {@link HomeworkSubmissionStatus} vs V006.
 */
public enum BlockAttemptStatus {

    /** Student has submitted moves; teacher hasn't reviewed yet. */
    SUBMITTED,

    /** Teacher reviewed the attempt and marked it correct / acceptable. */
    REVIEWED_OK,

    /**
     * Teacher reviewed the attempt and marked it as needing another try.
     * The student may subsequently unlock the recorded solution (that reveal
     * is stored as a separate timestamp, not an enum flip — see the class
     * comment).
     */
    REVIEWED_NEEDS_WORK
}
