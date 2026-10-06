-- Identity schema — users, credentials, memberships, and the tokens that
-- authenticate / recover them.
--
-- Design notes:
--   * A user row is global (not tenanted). A user is joined to a school by a
--     single `membership` row. PLATFORM_ADMIN users have no membership and
--     carry their role on `app_user.platform_role`. We do NOT support
--     multi-school users in the MVP (matches the "no role switching" rule).
--   * Passwords live in their own table so we can add other credential types
--     (oauth, magic link) later without bloating app_user.
--   * Refresh tokens use family-based rotation: every use mints a new token
--     in the same family; re-use of a revoked token revokes the whole family
--     (token-theft detection). Only SHA-256 hashes land in the DB.
--   * All opaque tokens (refresh, password reset, invitation) are stored as
--     32-byte SHA-256 hashes (hex-encoded) — the plaintext lives only in the
--     client that received it.

-- ============================================================================
-- app_user
-- ============================================================================

-- Email is stored as plain VARCHAR(240); we do *not* use the `citext`
-- extension because (a) Hibernate 6 schema-validation can't reconcile
-- citext (JDBC OTHER) with a String field (JDBC VARCHAR), and (b) the
-- functional-lower-index approach below is portable and explicit. All
-- application code lower-cases emails before insert / lookup.
CREATE TABLE app_user (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email             VARCHAR(240) NOT NULL,
    phone             VARCHAR(40),
    display_name      VARCHAR(160) NOT NULL,
    preferred_locale  VARCHAR(8) NOT NULL DEFAULT 'uz',
    status            VARCHAR(24) NOT NULL DEFAULT 'INVITED',
    platform_role     VARCHAR(24),
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT app_user_status_chk
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT app_user_platform_role_chk
        CHECK (platform_role IS NULL OR platform_role IN ('PLATFORM_ADMIN', 'PLATFORM_SUPPORT')),
    CONSTRAINT app_user_preferred_locale_chk
        CHECK (preferred_locale IN ('uz', 'ru', 'en'))
);

-- Case-insensitive uniqueness on email via functional index.
CREATE UNIQUE INDEX app_user_email_unique ON app_user (lower(email));
CREATE INDEX app_user_status_idx ON app_user (status);

-- ============================================================================
-- app_credentials — one password per user (for now)
-- ============================================================================

CREATE TABLE app_credentials (
    user_id           UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    password_hash     VARCHAR(255) NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    failed_attempts   INT NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- app_membership — user ↔ school ↔ role
-- ============================================================================

CREATE TABLE app_membership (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    school_id     UUID NOT NULL REFERENCES school(id) ON DELETE RESTRICT,
    role          VARCHAR(24) NOT NULL,
    status        VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT app_membership_role_chk
        CHECK (role IN ('SCHOOL_ADMIN', 'TEACHER', 'PARENT', 'STUDENT')),
    CONSTRAINT app_membership_status_chk
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    -- MVP: one membership per user.
    CONSTRAINT app_membership_user_unique UNIQUE (user_id)
);

CREATE INDEX app_membership_school_idx ON app_membership (school_id);
CREATE INDEX app_membership_school_role_idx ON app_membership (school_id, role);

-- ============================================================================
-- refresh_token — family-based rotation with reuse detection
-- ============================================================================

CREATE TABLE refresh_token (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id     UUID NOT NULL,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    replaced_by   UUID REFERENCES refresh_token(id) ON DELETE SET NULL,
    ip            VARCHAR(64),
    user_agent    VARCHAR(255)
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_expires_idx ON refresh_token (expires_at);

-- ============================================================================
-- password_reset_token — single use, short TTL
-- ============================================================================

CREATE TABLE password_reset_token (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ
);

CREATE INDEX password_reset_token_user_idx ON password_reset_token (user_id);

-- ============================================================================
-- invitation — issued by SCHOOL_ADMIN / PLATFORM_ADMIN for any role
-- ============================================================================

CREATE TABLE invitation (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     UUID REFERENCES school(id) ON DELETE CASCADE,
    role          VARCHAR(24) NOT NULL,
    email         VARCHAR(240) NOT NULL,
    display_name  VARCHAR(160) NOT NULL,
    invited_by    UUID REFERENCES app_user(id) ON DELETE SET NULL,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    accepted_at   TIMESTAMPTZ,
    accepted_user UUID REFERENCES app_user(id) ON DELETE SET NULL,
    metadata      JSONB,

    CONSTRAINT invitation_role_chk
        CHECK (role IN ('SCHOOL_ADMIN', 'TEACHER', 'PARENT', 'STUDENT', 'PLATFORM_ADMIN'))
);

CREATE INDEX invitation_school_idx ON invitation (school_id);
-- Case-insensitive lookup on pending invitations by email.
CREATE INDEX invitation_email_idx ON invitation (lower(email));

-- ============================================================================
-- parent_link — many-to-many between parent users and student users
-- ============================================================================

CREATE TABLE parent_link (
    parent_user_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    student_user_id  UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    school_id        UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (parent_user_id, student_user_id)
);

CREATE INDEX parent_link_student_idx ON parent_link (student_user_id);
CREATE INDEX parent_link_school_idx ON parent_link (school_id);
