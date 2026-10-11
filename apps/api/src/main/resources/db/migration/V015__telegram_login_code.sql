-- Telegram sign-in by one-time code (replaces the V013 deeplink handshake).
--
-- UX feedback on V013: "Log in with Telegram" opened a new tab, bounced the
-- user through the Telegram app, and then the original page changed under
-- them with no explicit step. The replacement is the pattern Telegram
-- itself uses for logging in: the user says who they are, the bot DMs a
-- 6-digit code, the user types it into the page.
--
--   browser: POST /auth/telegram/login-codes {identifier}   (username,
--            email or E.164 phone) → {id}; the bot sends the code to the
--            linked chat
--   browser: POST /auth/telegram/login-codes/{id}/verify {code} → JWT pair
--
-- Only the SHA-256 of the code is stored. 6 digits is brute-forceable in
-- principle, so a row allows at most 5 wrong attempts and lives 5 minutes;
-- after that it's dead and the user asks for a new code. A new request
-- within the cooldown window re-uses the pending row instead of spamming
-- the user's Telegram.

DROP TABLE IF EXISTS telegram_login_request;

CREATE TABLE telegram_login_code (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    code_hash     VARCHAR(128) NOT NULL,
    attempts      INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ
);

CREATE INDEX telegram_login_code_user_idx ON telegram_login_code (user_id, created_at DESC);
