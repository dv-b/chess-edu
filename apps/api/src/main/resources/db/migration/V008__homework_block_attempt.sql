-- Per-block submission attempts — the new work unit introduced by the
-- design pass in docs/design/student-solution-moves.md (Task #28).
--
-- A student's live answer for one block of one homework: the UCI moves they
-- played, the teacher's review outcome, and whether the student has chosen
-- to reveal the canonical solution. Attempts are history-preserving: when a
-- student resubmits (the "Overwrite & submit" flow in the mocks), the old
-- row's replaced_by_id is stamped and a fresh row is inserted. This way a
-- Needs-work review never gets silently overwritten by the next try —
-- teachers can scroll the chain if they want.
--
-- Relationship to homework_submission (V006):
--   * homework_submission survives; it's still the homework-level "DONE /
--     SKIPPED" self-mark the student may apply once every block is in a
--     terminal state (see design Q3 — kept as a tertiary micro-action).
--   * The per-block state the student and teacher actually interact with
--     now lives here. Absence of any homework_block_attempt row for
--     (hw, student, block) == "Not attempted".
--
-- Design notes:
--   * One row per attempt, not per (student, block). Overwrite = INSERT a
--     new row and set the previous row's replaced_by_id. The partial unique
--     index below enforces "at most one current (unreplaced) attempt per
--     (homework, student, block)" without preventing the history chain
--     from sharing that natural key.
--   * status mirrors the terminal block states in the design:
--       SUBMITTED              — student submitted, teacher hasn't reviewed
--       REVIEWED_OK            — teacher marked the attempt good
--       REVIEWED_NEEDS_WORK    — teacher marked the attempt needs work
--     The "Solution revealed" pill from the mocks is DERIVED: it's just
--     REVIEWED_NEEDS_WORK + solution_revealed_at IS NOT NULL. Keeping
--     reveal separate from the enum preserves the teacher's assessment even
--     after the student unlocks the answer.
--   * In-progress and not-attempted are deliberately NOT persisted: the
--     first is purely client-side (no autosave in v1), the second is the
--     absence of a row. Keeps the table small and the state machine honest.
--   * moves is stored as JSONB rather than TEXT[] for consistency with the
--     rest of the chess content surface (see homework_resource.payload,
--     which also holds UCI move arrays inside its JSONB). The validator
--     enforces array-of-strings shape on write.
--   * teacher_note is freeform TEXT. No markdown rendering server-side yet;
--     the frontend does any escaping needed.
--   * reviewed_by references app_user with ON DELETE SET NULL so losing a
--     teacher profile doesn't cascade-destroy student attempts. Same
--     pattern as homework.created_by.
--   * replaced_by_id is a self-referential UUID with ON DELETE SET NULL so
--     chain repair is still possible if we ever hard-delete an intermediate
--     row (today we don't — attempts are additive).
--   * status check constraint is duplicated in BlockAttemptStatus.java;
--     Flyway won't stop you, keep them in sync by convention (same
--     arrangement as HomeworkSubmissionStatus ↔ V006).

CREATE TABLE homework_block_attempt (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    homework_id              UUID NOT NULL REFERENCES homework(id)      ON DELETE CASCADE,
    student_user_id          UUID NOT NULL REFERENCES app_user(id)      ON DELETE CASCADE,
    block_index              INT  NOT NULL,
    moves                    JSONB NOT NULL DEFAULT '[]'::jsonb,
    status                   VARCHAR(32) NOT NULL,
    teacher_note             TEXT,
    reviewed_by              UUID        REFERENCES app_user(id)        ON DELETE SET NULL,
    reviewed_at              TIMESTAMPTZ,
    solution_revealed_at     TIMESTAMPTZ,
    replaced_by_id           UUID        REFERENCES homework_block_attempt(id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT homework_block_attempt_status_chk
        CHECK (status IN ('SUBMITTED', 'REVIEWED_OK', 'REVIEWED_NEEDS_WORK')),
    CONSTRAINT homework_block_attempt_block_index_nonneg_chk
        CHECK (block_index >= 0),
    CONSTRAINT homework_block_attempt_moves_is_array_chk
        CHECK (jsonb_typeof(moves) = 'array'),
    -- A reviewed attempt carries a reviewer + timestamp together; an
    -- un-reviewed attempt has neither. Guard the obvious inconsistency.
    CONSTRAINT homework_block_attempt_review_pair_chk
        CHECK (
            (reviewed_by IS NULL AND reviewed_at IS NULL)
            OR
            (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        ),
    -- Reveal only makes sense once the teacher has reviewed. The service
    -- enforces the stricter "reveal only after NEEDS_WORK" rule; here we
    -- just guarantee the timestamps line up.
    CONSTRAINT homework_block_attempt_reveal_after_review_chk
        CHECK (
            solution_revealed_at IS NULL
            OR reviewed_at IS NOT NULL
        )
);

-- "At most one live attempt per (homework, student, block)." History rows
-- (replaced_by_id IS NOT NULL) are excluded from the uniqueness check so
-- the chain of previous attempts can co-exist for audit / revisit.
CREATE UNIQUE INDEX homework_block_attempt_current_idx
    ON homework_block_attempt (homework_id, student_user_id, block_index)
    WHERE replaced_by_id IS NULL;

-- Homework-side batch read: "every student's attempts for this assignment"
-- — drives the teacher Submissions sheet with the 1–6 block strip.
CREATE INDEX homework_block_attempt_homework_idx
    ON homework_block_attempt (homework_id, student_user_id, block_index);

-- Student-side batch read: "my attempts across a set of homework ids" —
-- used by the student rollup list, mirrors the shape of
-- homework_submission_student_idx from V006.
CREATE INDEX homework_block_attempt_student_idx
    ON homework_block_attempt (student_user_id, homework_id);
