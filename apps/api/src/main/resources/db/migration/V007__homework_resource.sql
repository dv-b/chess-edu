-- Chess content blocks attached to a homework assignment.
--
-- Each row is one authored "thing" the student sees inside a homework:
-- a board position to study, a move sequence to play through, etc. The row
-- count per homework is expected to be small (< 20), so we don't bother
-- with a covering index beyond the homework_id lookup.
--
-- Design notes:
--   * One-to-many (homework 1 -- N resource). ON DELETE CASCADE so archiving
--     or hard-deleting a homework also removes its blocks.
--   * `position` orders blocks inside a homework. Unique per homework so a
--     swap-on-save strategy can't accidentally collapse two blocks onto the
--     same slot. We use an int rather than a bigserial because these are
--     user-facing ordinals, not global ids.
--   * `kind` is an open-ended string rather than an enum. We ship `BOARD`
--     in v1 but know we'll add `QUIZ`, `VIDEO`, `IMAGE` later without a
--     migration. Validation lives in the service, not the DB.
--   * `payload` is JSONB so the shape can evolve per kind without schema
--     churn. For `BOARD` the expected shape is:
--       {
--         "startingFen": "<FEN>",
--         "moves": ["e2e4", "e7e5", ...],            -- UCI, may be empty
--         "orientation": "white" | "black",
--         "solution": { "uci": ["g4e5", ...] }        -- optional
--       }
--     Legality is re-checked by the service on every write.
--   * `title` is optional; most homeworks have a single board block and the
--     homework's own title is enough. Multi-block homeworks want per-block
--     labels ("Puzzle 1", "Puzzle 2"), so we keep the column.

CREATE TABLE homework_resource (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    homework_id   UUID NOT NULL REFERENCES homework(id) ON DELETE CASCADE,
    position      INT  NOT NULL,
    kind          VARCHAR(24) NOT NULL,
    title         VARCHAR(200),
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT homework_resource_kind_nonblank_chk
        CHECK (length(btrim(kind)) > 0),
    CONSTRAINT homework_resource_position_nonneg_chk
        CHECK (position >= 0),
    CONSTRAINT homework_resource_unique_position
        UNIQUE (homework_id, position)
);

-- Primary read pattern: "give me all blocks for this homework, in order".
-- The UNIQUE constraint already creates an index on (homework_id, position),
-- which covers this read; no extra index needed.
