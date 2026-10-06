-- Homework — a task a teacher assigns to a class (optionally tied to the
-- specific class_session in which it was given), plus a per-student
-- submission row tracking whether the student has completed it.
--
-- Design notes:
--   * Grain of `homework_submission` is (homework_id, student_user_id). A
--     missing row means "not yet started" rather than "skipped" — this
--     mirrors how session_attendance handles unmarked roster members, so
--     the UI can distinguish "the student hasn't touched this" from "the
--     student marked it skipped". Rollups must join the class roster, not
--     rely on submission rows alone.
--   * A homework is assigned to a class (required) and optionally to a
--     specific session. We keep the session link nullable because not every
--     assignment is given in a live session — a teacher might post "do
--     these puzzles before next week" between sessions via the admin UI.
--     If the session is later deleted, we keep the homework but clear the
--     session link (ON DELETE SET NULL).
--   * `archived_at` gives us a soft-delete: a teacher removes an assignment
--     from the active list but the submission trail survives for reports.
--     We don't hard-delete homework with submissions because parents and
--     admins will ask "did my kid do X?" months later.
--   * `due_at` is nullable. Not all assignments have a hard deadline; the
--     UI can nudge with "due today" only when it's set.
--   * student_user_id and marked_by reference app_user directly rather than
--     class_student — students move between classes and we want the
--     submission trail to survive a roster edit. FK to app_user is
--     ON DELETE CASCADE for submissions (if a student profile is fully
--     deleted, their work history goes with them) and ON DELETE SET NULL
--     for marked_by / created_by (we keep the row but lose the pointer).
--   * status enum is duplicated between this CHECK constraint and
--     HomeworkSubmissionStatus in code. Keep them in sync by convention;
--     Flyway won't stop you.

-- ============================================================================
-- homework — the assignment row itself
-- ============================================================================

CREATE TABLE homework (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id       UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,
    session_id     UUID REFERENCES class_session(id) ON DELETE SET NULL,
    title          VARCHAR(200) NOT NULL,
    instructions   TEXT,
    due_at         TIMESTAMPTZ,
    created_by     UUID REFERENCES app_user(id) ON DELETE SET NULL,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT homework_title_nonblank_chk
        CHECK (length(btrim(title)) > 0)
);

-- Class-side reads: "show me homework for this class", ordered by
-- most-recently-created first. One index covers both the "Active" tab
-- (with a WHERE archived_at IS NULL filter in the query) and the rarer
-- "All" tab. A class's homework list is small enough that the archived-
-- filter predicate runs cheaply on top of the index scan.
CREATE INDEX homework_class_created_idx
    ON homework (class_id, created_at DESC);

-- Occasional "what did this session produce?" lookups from the session UI.
CREATE INDEX homework_session_idx
    ON homework (session_id)
    WHERE session_id IS NOT NULL;

-- ============================================================================
-- homework_submission — per-student status for one homework
-- ============================================================================

CREATE TABLE homework_submission (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    homework_id       UUID NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
    student_user_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status            VARCHAR(16) NOT NULL,
    student_note      TEXT,
    submitted_at      TIMESTAMPTZ,
    marked_by         UUID REFERENCES app_user(id) ON DELETE SET NULL,
    marked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT homework_submission_status_chk
        CHECK (status IN ('NOT_STARTED', 'DONE', 'SKIPPED')),
    CONSTRAINT homework_submission_unique_per_student
        UNIQUE (homework_id, student_user_id)
);

-- Homework-side reads: "show me everyone's submission status for this
-- assignment" — the admin detail view.
CREATE INDEX homework_submission_homework_idx
    ON homework_submission (homework_id);

-- Student-side reads: "show me my open homework across all classes". We
-- include the status so the DB can filter NOT_STARTED without hitting the
-- row. The student-side list is also ordered by marked_at DESC for recency.
CREATE INDEX homework_submission_student_idx
    ON homework_submission (student_user_id, status, marked_at DESC);
