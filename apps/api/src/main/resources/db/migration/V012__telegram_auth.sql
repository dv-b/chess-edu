-- Telegram-based signup / sign-in for non-student roles.
--
-- First slice of the Telegram-auth umbrella (Task A). Adds the persistence
-- layer for two intertwined flows:
--
--   * Initial signup (bot-driven, captures a Telegram-verified phone):
--       /start INV_<token>  →  bot validates the invitation  →  "Share
--       contact" keyboard button  →  bot creates the app_user +
--       telegram_account + redeems the invitation  →  bot replies with a
--       one-time /auth/accept?ott=<token> deeplink  →  web redeems for JWT.
--   * Repeat sign-in (web Login Widget, faster, no bot DM):
--       Login Widget callback, HMAC-verified against the bot token, keyed
--       on the same telegram_account row by telegram_id  →  web mints an
--       OTT internally and redeems  →  JWT. (Widget endpoint lands later;
--       the schema is shaped for it now.)
--
-- Both flows funnel through the SAME `telegram_auth_token` one-time-token
-- table for the final web handoff, so there's one redemption path on the
-- web side regardless of which flow the user came from.
--
-- Design notes:
--   * `telegram_account` is a 1:1 side table on app_user (UNIQUE user_id)
--     rather than columns on app_user, because (a) most users — every
--     student, plus any staff who only ever use email/password — never
--     get a Telegram link, so the columns would be mostly NULL; and (b)
--     it keeps the Telegram-specific profile snapshot (username, photo,
--     language) out of the core identity row. Mirrors the "credentials in
--     their own table" decision from V002.
--   * `telegram_id` is the stable Telegram-side primary key (a BIGINT that
--     never changes for a user); `username` / `first_name` / `photo_url`
--     are mutable profile fields we snapshot on each interaction. We key
--     all lookups on `telegram_id`, never on `username` (usernames can be
--     changed or recycled).
--   * `phone` is E.164, captured via Telegram's native "Share Contact"
--     keyboard button — it's Telegram-verified, not user-typed. Nullable
--     because the Login-Widget repeat-sign-in path doesn't re-collect it
--     (it's already on the row from signup).
--   * `telegram_auth_token` mirrors `password_reset_token` from V002
--     line-for-line: 32-byte SHA-256 hex hash stored, plaintext only ever
--     lives in the deeplink handed to the user, single-use, short TTL.
--   * The `invitation` extension adds a delivery channel (how the invite
--     is sent: EMAIL — the existing path — or TELEGRAM) and a bot-funnel
--     state so the admin invitation list can show where a Telegram invite
--     is in the conversation (issued → user started the bot → awaiting the
--     contact share → completed / cancelled). Both are CHECK-constrained
--     VARCHARs, open enough to extend without a migration, same approach as
--     `lesson_resource.kind` / `block_question.parent_kind`.

-- ============================================================================
-- telegram_account — 1:1 link between an app_user and a Telegram identity
-- ============================================================================

CREATE TABLE telegram_account (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- One Telegram link per user, one user per Telegram identity. The
    -- UNIQUE on user_id enforces the "single trust path" rule; the UNIQUE
    -- on telegram_id stops two app_users claiming the same Telegram
    -- account.
    user_id         UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    telegram_id     BIGINT NOT NULL UNIQUE,

    -- Mutable Telegram profile snapshot, refreshed on each interaction.
    -- All nullable: a Telegram account need not have a @username, and the
    -- Login-Widget payload omits some fields the bot conversation carries.
    username        VARCHAR(64),
    first_name      VARCHAR(128),
    last_name       VARCHAR(128),
    photo_url       VARCHAR(512),

    -- E.164 phone, Telegram-verified via the "Share Contact" button. Null
    -- on rows that only ever went through the Login Widget (phone already
    -- captured at signup).
    phone           VARCHAR(32),

    language_code   VARCHAR(16),

    -- The private-chat id the bot uses to DM this user (== telegram_id for
    -- a private chat, but stored separately so a future group/channel link
    -- doesn't conflate the two).
    chat_id         BIGINT,

    -- Domain timestamps (not created_at/updated_at) — set explicitly by
    -- the service, matching the refresh_token / password_reset_token
    -- convention in this same identity domain rather than the auditing
    -- listener used by app_user.
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,

    CONSTRAINT telegram_account_phone_e164_chk
        CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,15}$')
);

CREATE INDEX telegram_account_user_idx ON telegram_account (user_id);

-- ============================================================================
-- telegram_auth_token — one-time bot→web (and widget→web) handoff token
-- ============================================================================

CREATE TABLE telegram_auth_token (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ
);

CREATE INDEX telegram_auth_token_user_idx ON telegram_auth_token (user_id);

-- ============================================================================
-- invitation — extend with delivery channel + bot funnel state
-- ============================================================================

-- Default EMAIL so every existing row (and any code path that doesn't set
-- the channel) keeps the current behaviour.
ALTER TABLE invitation
    ADD COLUMN delivery_channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL';

-- Funnel state for the Telegram delivery channel. NULL for EMAIL invites
-- (the funnel concept doesn't apply). For TELEGRAM invites: PENDING when
-- issued, STARTED once the user runs /start <token>, AWAITING_CONTACT while
-- the bot waits for the Share-Contact tap, COMPLETED when the account is
-- created + invitation redeemed, CANCELLED if the user backs out via
-- /cancel.
ALTER TABLE invitation
    ADD COLUMN bot_state VARCHAR(24);

ALTER TABLE invitation
    ADD CONSTRAINT invitation_delivery_channel_chk
        CHECK (delivery_channel IN ('EMAIL', 'TELEGRAM'));

ALTER TABLE invitation
    ADD CONSTRAINT invitation_bot_state_chk
        CHECK (bot_state IS NULL OR bot_state IN
            ('PENDING', 'STARTED', 'AWAITING_CONTACT', 'COMPLETED', 'CANCELLED'));
