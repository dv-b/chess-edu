-- Block-level Q&A — students ask, teachers reply.
--
-- The first slice of the classroom-comms umbrella (Task #65). Lets a
-- student ask a question pinned to a specific block on a homework or a
-- lesson, and lets the teacher reply / resolve. The same surface mounts
-- on both parent kinds because the data model is identical — the only
-- thing that changes per kind is which table the `parent_id` resolves
-- to. We deliberately keep them in one table rather than splitting into
-- `homework_block_question` + `lesson_block_question`:
--
--   * The teacher inbox (the primary read path) is "show me unresolved
--     threads across both kinds, oldest first" — a UNION across two
--     tables would either need a view, or a denormalised projection,
--     or two parallel queries the service hand-merges. None of those
--     buy anything except more code.
--   * The trade-off is no real FK on `parent_id` (it could point at a
--     homework or a lesson). Acceptable because we never hard-delete
--     either parent — both archive via `archived_at`. A question
--     attached to an archived parent stays valid; the inbox just hides
--     archived threads via the service-layer filter.
--   * `parent_kind` is a CHECK-constrained VARCHAR (HOMEWORK, LESSON)
--     — open enough to add e.g. `SESSION` or `STUDENT` later without a
--     migration. Same approach as `lesson_resource.kind`.
--
-- Replies are a separate table because threads can grow past a single
-- back-and-forth ("I tried 1.e4, why didn't it work?" → "What did you
-- expect Black to play?" → ...). Storing them inline as a JSONB array
-- on the question would hurt the inbox query (we'd have to read the
-- whole array to count "has any reply" / "last reply at"), and forking
-- the schema later would be painful.
--
-- Authorship + role:
--   * `student_user_id` on the question is the asker — always a
--     STUDENT role at write time, but we don't denormalise the role
--     because role assignments evolve over time (a student can graduate
--     into a teacher etc.). The service authorises on read.
--   * `author_user_id` on the reply is whoever sent it — could be the
--     student following up, or a teacher. Again, no denormalised role.
--
-- Resolution:
--   * `resolved_at` + `resolved_by` track the close. Either the teacher
--     or the asker can resolve (auto-resolve when the asker says
--     "thanks, got it") — same shape both ways.
--   * Re-opening: deferred to a follow-up. v1 is one-way close. If a
--     resolved thread needs more discussion, the student opens a fresh
--     question on the same block.

-- ============================================================================
-- block_question — the head of a thread
-- ============================================================================

CREATE TABLE block_question (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Denormalised so the inbox roll-up doesn't have to JOIN to homework
    -- + lesson to figure out which class a thread belongs to. Same trick
    -- the homework / lesson tables use. ON DELETE CASCADE so dropping a
    -- class wipes its conversation history (no legal retention yet).
    class_id           UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,

    -- Polymorphic parent reference. Validated by service-layer code on
    -- write (must point at a non-archived row in the matching table).
    parent_kind        VARCHAR(16) NOT NULL,
    parent_id          UUID NOT NULL,

    -- Block index inside the parent's authored content list. Bound is
    -- per-parent (homework_resource / lesson_resource use a 0-based
    -- position UNIQUE per parent), so 0..255 is plenty of headroom for
    -- the foreseeable future.
    block_index        INT NOT NULL,

    -- The asker. STUDENT role expected at write time but the service
    -- enforces that, not the schema (a future "teacher demonstrates
    -- a question to the class" feature could lift the constraint).
    student_user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    body               TEXT NOT NULL,

    -- Resolution. Both nullable so an open thread reads as
    -- (resolved_at IS NULL, resolved_by IS NULL). When a teacher /
    -- asker closes, both stamp at the same time.
    resolved_at        TIMESTAMPTZ,
    resolved_by        UUID REFERENCES app_user(id) ON DELETE SET NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT block_question_parent_kind_chk
        CHECK (parent_kind IN ('HOMEWORK', 'LESSON')),
    CONSTRAINT block_question_block_index_nonneg_chk
        CHECK (block_index >= 0),
    CONSTRAINT block_question_body_nonblank_chk
        CHECK (length(btrim(body)) > 0),
    CONSTRAINT block_question_body_length_chk
        CHECK (length(body) <= 2000),
    -- If resolved_at is set, resolved_by must also be set (and vice
    -- versa). Belt-and-suspenders for the service layer.
    CONSTRAINT block_question_resolution_paired_chk
        CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))
);

-- Student-side read: "show me my open threads on this block".
-- Composite over (parent_kind, parent_id, block_index, student_user_id)
-- — the natural compound key for "fetch the thread anchored to (block,
-- student)". A student typically has zero or one open thread per block
-- but we don't enforce uniqueness because (a) a thread can be resolved
-- and the student can open a fresh one, and (b) a future feature might
-- let a student keep multiple open threads (one per follow-up question).
CREATE INDEX block_question_student_block_idx
    ON block_question (parent_kind, parent_id, block_index, student_user_id);

-- Inbox roll-up: "unresolved threads in this class, oldest first".
-- Partial index on the unresolved subset because that's >95% of the
-- read traffic — once a thread is resolved, the only read path is the
-- per-block thread reconstruction, which goes through the composite
-- above. Backing the partial index halves the inbox-query cost on a
-- class with a long historical Q&A trail.
CREATE INDEX block_question_class_open_idx
    ON block_question (class_id, created_at)
    WHERE resolved_at IS NULL;

-- Per-block thread reconstruction in the admin block-review surface
-- (Task #28-style overlay). Same composite as the student-side read
-- but without the student filter — a teacher sees every student's
-- thread on a given block.
CREATE INDEX block_question_parent_block_idx
    ON block_question (parent_kind, parent_id, block_index);

-- ============================================================================
-- block_question_reply — ordered replies under a question
-- ============================================================================

CREATE TABLE block_question_reply (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id        UUID NOT NULL REFERENCES block_question(id) ON DELETE CASCADE,

    -- Whoever posted the reply. Could be the asking student following
    -- up, or a teacher / admin responding. Service-layer authorises;
    -- the schema is permissive.
    author_user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    body               TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT block_question_reply_body_nonblank_chk
        CHECK (length(btrim(body)) > 0),
    CONSTRAINT block_question_reply_body_length_chk
        CHECK (length(body) <= 2000)
);

-- Thread view: "show every reply on this question, oldest first."
-- The PK index already covers single-row lookups; this composite makes
-- the ordered-list read a single index scan.
CREATE INDEX block_question_reply_thread_idx
    ON block_question_reply (question_id, created_at);
