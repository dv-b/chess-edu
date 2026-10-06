-- Baseline migration — the tenant table and supporting types.
-- Cluster-level extensions are already enabled by infra/docker/postgres-init.sql.

-- ============================================================================
-- Schools (tenants)
-- ============================================================================
--
-- A school is the tenant row. Every other domain table will carry a
-- school_id FK and Hibernate will apply an @Filter so cross-tenant queries
-- return empty sets even when a dev forgets to add the where-clause.

-- slug is plain VARCHAR (not citext) for Hibernate-compatibility; uniqueness is
-- enforced case-insensitively via a lower() functional index below. Application
-- code stores slugs lower-cased.
-- country_code is VARCHAR(2) (not CHAR(2)) — CHAR maps to bpchar in Postgres,
-- which Hibernate's schema-validation rejects as a mismatch with String fields.
CREATE TABLE school (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(64) NOT NULL,
    display_name    VARCHAR(160) NOT NULL,
    legal_name      VARCHAR(240),
    country_code    VARCHAR(2) NOT NULL DEFAULT 'UZ',
    default_locale  VARCHAR(8) NOT NULL DEFAULT 'uz',
    timezone        VARCHAR(64) NOT NULL DEFAULT 'Asia/Tashkent',
    status          VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    billing_email   VARCHAR(240),
    contact_phone   VARCHAR(40),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT school_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT school_default_locale_chk CHECK (default_locale IN ('uz', 'ru', 'en'))
);

CREATE UNIQUE INDEX school_slug_unique ON school (lower(slug));
CREATE INDEX school_status_idx ON school (status);

-- ============================================================================
-- Seed: one synthetic school for local development
-- ============================================================================

INSERT INTO school (slug, display_name, legal_name, country_code, default_locale, status, billing_email)
VALUES
    ('demo', 'Demo Chess School', 'Demo Chess School LLC', 'UZ', 'uz', 'ACTIVE', 'admin@demo.local')
ON CONFLICT (lower(slug)) DO NOTHING;
