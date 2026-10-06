-- Classes — groups of students who meet with one or more teachers at a
-- given skill level. "Class" is a Java reserved word, so the table and
-- entity are named `class_group` / `ClassGroup` consistently throughout.
--
-- Design notes:
--   * Teachers and students are both app_user rows with a TEACHER or STUDENT
--     membership in the same school as the class. We enforce that at the
--     service layer, not via CHECK/FK — the membership role can change (e.g.
--     a teacher is archived) and we'd rather let the UI render a warning than
--     have a DB insert suddenly fail.
--   * A class can have multiple teachers (lead + assistant(s)) from day one.
--     We don't model "lead" explicitly yet — the client can treat the first
--     teacher alphabetically as lead, or we add a flag later.
--   * Junction tables use composite PKs (no surrogate id). Both use
--     ON DELETE CASCADE so removing a class or a user tears down the links.
--   * `status` mirrors Membership: ACTIVE / ARCHIVED. "Suspended" isn't
--     meaningful for a class; if a teacher wants to pause the class,
--     ARCHIVED + a new class captures that cleanly.

-- ============================================================================
-- class_group — the class row itself
-- ============================================================================

CREATE TABLE class_group (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    level         VARCHAR(32),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT class_group_status_chk
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT class_group_level_chk
        CHECK (level IS NULL OR level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED'))
);

CREATE INDEX class_group_school_idx ON class_group (school_id);
CREATE INDEX class_group_school_status_idx ON class_group (school_id, status);
-- Case-insensitive name search inside a school.
CREATE INDEX class_group_name_lower_idx ON class_group (school_id, lower(name));

-- ============================================================================
-- class_teacher — which teachers lead / assist a given class
-- ============================================================================

CREATE TABLE class_teacher (
    class_id         UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,
    teacher_user_id  UUID NOT NULL REFERENCES app_user(id)   ON DELETE CASCADE,
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (class_id, teacher_user_id)
);

CREATE INDEX class_teacher_user_idx ON class_teacher (teacher_user_id);

-- ============================================================================
-- class_student — enrolment of students into a class
-- ============================================================================

CREATE TABLE class_student (
    class_id         UUID NOT NULL REFERENCES class_group(id) ON DELETE CASCADE,
    student_user_id  UUID NOT NULL REFERENCES app_user(id)   ON DELETE CASCADE,
    enrolled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (class_id, student_user_id)
);

CREATE INDEX class_student_user_idx ON class_student (student_user_id);
