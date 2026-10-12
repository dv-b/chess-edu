-- Telegram repeat sign-in without a production domain (auth roadmap item 2).
--
-- The Login Widget needs a domain registered with @BotFather, which we don't
-- have yet. Until then, re-login uses a "login request" handshake:
--
--   browser: POST /auth/telegram/login-requests  →  {id, nonce, clientSecret}
--            opens  https://t.me/<bot>?start=login_<nonce>  and polls
--   bot:     /start login_<nonce> from an already-linked Telegram account
--            → marks the request completed for that user
--   browser: POST /auth/telegram/login-requests/{id}/poll {clientSecret}
--            → once completed: a JWT pair, request marked consumed
--
-- Three secrets, three parties: the NONCE travels to Telegram (user-visible
-- in the deeplink) and is the only thing the bot can act on; the CLIENT
-- SECRET never leaves the browser's HttpOnly cookie and is the only thing
-- that can turn a completed request into a session; the request ID is just
-- a handle. All stored hashed, same rule as every other token table.
-- Short TTL, single-use (consumed_at). Rows are cheap and self-expiring —
-- no cleanup job in v1.

CREATE TABLE telegram_login_request (
    id                  UUID PRIMARY KEY,
    nonce_hash          VARCHAR(128) NOT NULL UNIQUE,
    client_secret_hash  VARCHAR(128) NOT NULL,

    -- Set by the bot on completion. Nullable until then.
    user_id             UUID REFERENCES app_user(id) ON DELETE CASCADE,
    telegram_id         BIGINT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    consumed_at         TIMESTAMPTZ
);

CREATE INDEX telegram_login_request_expires_idx ON telegram_login_request (expires_at);
