-- Username as a second login identifier (auth roadmap item 3).
--
-- Email stays the primary identity (NOT NULL, globally unique). The
-- username exists because (a) students created by a parent carry a
-- synthetic internal email they never see, so their own-credentials login
-- needs something typeable; and (b) Telegram-signup users may want a
-- password fallback that doesn't depend on remembering which email the
-- admin typed into the invitation.
--
-- Rules, enforced here and mirrored in AccountService:
--   * optional (NULL until the user picks one);
--   * stored lowercase; uniqueness is case-insensitive via lower();
--   * 3–32 chars of [a-z0-9_.], starting with a letter or digit — no '@',
--     so the login endpoint can tell emails and usernames apart by the
--     presence of '@'.

ALTER TABLE app_user
    ADD COLUMN username VARCHAR(32);

ALTER TABLE app_user
    ADD CONSTRAINT app_user_username_chk
        CHECK (username IS NULL OR username ~ '^[a-z0-9][a-z0-9_.]{2,31}$');

CREATE UNIQUE INDEX app_user_username_unique
    ON app_user (lower(username))
    WHERE username IS NOT NULL;
