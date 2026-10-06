-- Attendance — one row per (session, student) recording whether the student
-- showed up, was late, was absent, or was excused. Keyed off class_session
-- so cancelling or deleting a session cleans the attendance rows with it.
--
-- Design notes:
--   * Grain is (session_id, student_user_id). A student either has a row or
--     they don't — a missing row means "not yet marked", not "absent", so
--     teachers can record partial data without the UI lying about the rest
--     of the roster. Attendance rollups should always join against the
--     class roster at report time, not this table alone.
--   * student_user_id references app_user(id) directly rather than
--     class_student — students sometimes move between classes mid-term and
--     we want the historical row to survive a roster edit. FK to app_user
--     is ON DELETE CASCADE because if a child profile is fully archived and
--     later deleted, we want the attendance trail to go with them.
--   * marked_by is NULLABLE on purpose. When an admin back-fills a "mass
--     present" entry from the UI we record them, but if a future scheduled
--     task ever writes attendance (e.g. sync from a check-in device) we
--     don't want to force a synthetic user row.
--   * status enum lives both here (CHECK) and in code (AttendanceStatus).
--     Keep them in sync by convention; Flyway won't stop you.

CREATE TABLE session_attendance (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL REFERENCES class_session(id) ON DELETE CASCADE,
    student_user_id  UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status           VARCHAR(16) NOT NULL,
    note             TEXT,
    marked_by        UUID REFERENCES app_user(id) ON DELETE SET NULL,
    marked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT session_attendance_status_chk
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'EXCUSED')),
    CONSTRAINT session_attendance_unique_per_student
        UNIQUE (session_id, student_user_id)
);

-- Session-side reads: "show me the attendance sheet for this session".
CREATE INDEX session_attendance_session_idx
    ON session_attendance (session_id);

-- Student-side rollups: "show me this child's last N sessions". Ordered by
-- marked_at DESC in the queries that will use this — the B-tree supports
-- both directions.
CREATE INDEX session_attendance_student_time_idx
    ON session_attendance (student_user_id, marked_at);
