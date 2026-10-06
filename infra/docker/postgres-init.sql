-- Runs once, the first time the Postgres container's data directory is empty.
-- Schema DDL lives in Flyway migrations; this file is only for cluster-level setup.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";

-- Default timezone for sessions that don't override it.
ALTER DATABASE chessedu SET timezone TO 'Asia/Tashkent';
