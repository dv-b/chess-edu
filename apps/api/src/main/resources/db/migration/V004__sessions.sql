-- Sessions — concrete meetings of a class, and the (optional) weekly pattern
-- that generates them. Attendance and homework will hang off class_session
-- once those features land; for now this milestone is just "when are we
-- meeting?" with an explicit horizon of pre-materialised future sessions.
--
-- Design notes:
--   * A class may have zero or more schedule patterns. Mon+Wed = two rows
--     sharing class_id. We intentionally do NOT model it as "days bitmask"
--     — one row per weekday makes edits (drop Monday, keep Wednesday)
--     trivial, and the table stays readable with a single CHECK per column.
--   * Patterns carry an IANA timezone so weekly materialisation handles DST
--     correctly — "17:00 on Tuesdays in Asia/Tashkent" is unambiguous even
--     if the rules of that zone change. Defaults to 'Asia/Tashkent' since
--     that's our launch market.
--   * class_session.starts_at / ends_at are TIMESTAMPTZ (UTC instants).
--     The pattern's local time + zone resolves to those instants at
--     generation time and never needs to be re-resolved for display.
--   * pattern_id is ON DELETE SET NULL — dropping a pattern doesn't delete
--     history; the sessions just detach and can be edited/cancelled on
--     their own. One-off ad-hoc sessions are born with pattern_id = NULL.
--   * status ∈ {SCHEDULED, CANCELLED, COMPLETED}. We don't model "in progress"
--     — it's derivable from (starts_at <= now < ends_at AND status = SCHEDULED).

-- ============================================================================
-- class_schedule_pattern — weekly recurring rule
-- ============================================================================

CREATE TABLE class_schedule_pattern (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id      UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,
    day_of_week   SMALLINT NOT NULL,     -- ISO-8601: 1=Mon .. 7=Sun
    start_time    TIME NOT NULL,         -- local time in `timezone`
    end_time      TIME NOT NULL,         -- local time in `timezone`, > start_time
    starts_on     DATE NOT NULL,         -- first eligible date (inclusive)
    ends_on       DATE,                  -- last eligible date (inclusive); NULL = open-ended
    timezone      VARCHAR(64) NOT NULL DEFAULT 'Asia/Tashkent',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT class_schedule_pattern_day_chk
        CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT class_schedule_pattern_time_chk
        CHECK (end_time > start_time),
    CONSTRAINT class_schedule_pattern_range_chk
        CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

CREATE INDEX class_schedule_pattern_class_idx ON class_schedule_pattern (class_id);

-- ============================================================================
-- class_session — concrete meeting
-- ============================================================================

CREATE TABLE class_session (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id      UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,
    pattern_id    UUID REFERENCES class_schedule_pattern(id) ON DELETE SET NULL,
    starts_at     TIMESTAMPTZ NOT NULL,  -- UTC instant
    ends_at       TIMESTAMPTZ NOT NULL,  -- UTC instant, > starts_at
    status        VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT class_session_time_chk
        CHECK (ends_at > starts_at),
    CONSTRAINT class_session_status_chk
        CHECK (status IN ('SCHEDULED', 'CANCELLED', 'COMPLETED'))
);

-- Timeline queries for a single class.
CREATE INDEX class_session_class_time_idx ON class_session (class_id, starts_at);
-- Regeneration queries: "all future sessions owned by this pattern".
CREATE INDEX class_session_pattern_idx ON class_session (pattern_id) WHERE pattern_id IS NOT NULL;
-- Prevent double-materialisation of the same pattern-day pair. Two patterns
-- for the same class legitimately can share a weekday (e.g. a split schedule
-- with different times), so we key on pattern_id + starts_at, not class_id.
CREATE UNIQUE INDEX class_session_pattern_start_uniq
    ON class_session (pattern_id, starts_at)
    WHERE pattern_id IS NOT NULL;
