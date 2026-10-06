-- Lessons — study materials teachers publish to a class.
--
-- Lessons are the "study" side of the curriculum, parallel to homework
-- (the "solve" side). A homework is something a student *does* (plays a
-- move, solves a puzzle, marks done); a lesson is something a student
-- *reads / watches / walks through*. The two live on the same class but
-- in separate tables because:
--
--   * Lessons have no due date (`homework.due_at`-style nagging is
--     inappropriate for study material — students should be encouraged
--     to revisit lessons, not dinged for it).
--   * Lesson completion is binary (READ or not) and optional per student
--     — a lesson can be published without an assignment expectation,
--     whereas homework is always "this specific student needs to do X".
--   * Lesson block kinds (PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO) differ
--     from homework's BOARD-only content space, so mixing them in one
--     `homework_resource` table would muddy the semantics of
--     `homework_block_attempt` (which only makes sense for solve blocks).
--
-- The three tables in this migration mirror the homework trio:
--   * `lesson`               ~ `homework`              (minus `due_at`)
--   * `lesson_resource`      ~ `homework_resource`     (broader kind set)
--   * `lesson_completion`    ~ `homework_submission`   (binary READ state)
--
-- Design notes:
--   * Archived (soft-deleted) lessons keep their completion trail so
--     "did the student read the opening principles lesson last term?"
--     still has an answer.
--   * `archived_at` hides from the active list; hard delete cascades
--     to resources + completions (no legal retention requirement yet).
--   * `session_id` is optional (ON DELETE SET NULL): a teacher might
--     anchor a lesson to the session in which they taught it, but
--     deleting the session shouldn't evict the lesson from the library.
--   * `kind` is an open-ended VARCHAR (not an enum) — service-layer
--     validation owns the allowed values. v1 ships PROSE /
--     BOARD_WALKTHROUGH / MCQ / VIDEO but we want to add AUDIO / QUIZ
--     / PUZZLE_SET later without a migration.
--   * `payload` is JSONB: shape evolves per kind, validated on write.
--   * `lesson_completion.status` is a CHECK-constrained VARCHAR with two
--     values. We store NOT_STARTED explicitly (not just absent rows) so
--     a student who opens-and-resets a lesson can be told apart from one
--     who's never touched it — same pattern we use for
--     `homework_submission`.

-- ============================================================================
-- lesson — the study material "page"
-- ============================================================================

CREATE TABLE lesson (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id       UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,
    session_id     UUID REFERENCES class_session(id) ON DELETE SET NULL,
    title          VARCHAR(200) NOT NULL,
    instructions   TEXT,
    created_by     UUID REFERENCES app_user(id) ON DELETE SET NULL,
    archived_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT lesson_title_nonblank_chk
        CHECK (length(btrim(title)) > 0)
);

-- Class-side reads: "show me lessons for this class", newest first.
-- Same index shape as homework_class_created_idx; the "Active" tab
-- layers a `WHERE archived_at IS NULL` on top which filters cheaply.
CREATE INDEX lesson_class_created_idx
    ON lesson (class_id, created_at DESC);

-- Occasional "what did this session cover?" lookups from the session UI.
CREATE INDEX lesson_session_idx
    ON lesson (session_id)
    WHERE session_id IS NOT NULL;

-- ============================================================================
-- lesson_resource — ordered content blocks inside a lesson
-- ============================================================================

CREATE TABLE lesson_resource (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id     UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    position      INT  NOT NULL,
    kind          VARCHAR(32) NOT NULL,
    title         VARCHAR(200),
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT lesson_resource_kind_nonblank_chk
        CHECK (length(btrim(kind)) > 0),
    CONSTRAINT lesson_resource_position_nonneg_chk
        CHECK (position >= 0),
    CONSTRAINT lesson_resource_unique_position
        UNIQUE (lesson_id, position)
);

-- The UNIQUE constraint's backing index already covers the primary
-- "list blocks for a lesson in order" read pattern.

-- ============================================================================
-- lesson_completion — per-student read state
-- ============================================================================

CREATE TABLE lesson_completion (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id         UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    student_user_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status            VARCHAR(16) NOT NULL,
    marked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT lesson_completion_status_chk
        CHECK (status IN ('NOT_STARTED', 'READ')),
    CONSTRAINT lesson_completion_unique_per_student
        UNIQUE (lesson_id, student_user_id)
);

-- Lesson-side reads: "who in this class has read this lesson?" — admin
-- roll-up.
CREATE INDEX lesson_completion_lesson_idx
    ON lesson_completion (lesson_id);

-- Student-side reads: "how many unread lessons do I have?" — powers
-- the student sidebar badge.
CREATE INDEX lesson_completion_student_idx
    ON lesson_completion (student_user_id, status, marked_at DESC);
