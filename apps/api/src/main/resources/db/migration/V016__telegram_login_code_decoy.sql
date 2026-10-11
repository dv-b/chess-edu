-- Decoy login-code rows for unmatched identifiers.
--
-- V015's request endpoint answered an unmatched identifier with a random id
-- that no row backed. That looked identical at request time, but the verify
-- step gave it away: a real row answered a wrong code with 401, the fake id
-- with 404. An attacker could therefore confirm "username X exists" with
-- one request + one bogus verify.
--
-- Now an unmatched identifier gets a real row with no user (and no DM):
-- same TTL, same attempt counting, same 401 / 410 behaviour. It simply
-- never verifies. Hence user_id becomes nullable.

ALTER TABLE telegram_login_code ALTER COLUMN user_id DROP NOT NULL;
