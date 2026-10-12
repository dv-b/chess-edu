-- Phone-matched Telegram invitations (no link to forward).
--
-- A Telegram bot cannot message someone who never pressed Start, so the
-- per-invite deeplink had to be copied by the admin and forwarded by hand.
-- Schools do have parents' and teachers' phone numbers, and the bot already
-- receives a Telegram-verified phone via "Share Contact". So: the admin
-- records the invitee's phone on the invitation; the invitee opens the bot
-- from any generic link / QR and presses Start; the bot asks for the
-- contact and matches the pending invitation by phone. The token deeplink
-- remains as the fallback when the school has no number.
--
-- When a phone is set on a token invitation, the shared contact must match
-- it (defence in depth against a forwarded link landing with the wrong
-- person).

ALTER TABLE invitation
    ADD COLUMN phone VARCHAR(32);

ALTER TABLE invitation
    ADD CONSTRAINT invitation_phone_e164_chk
        CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,15}$');

-- The bot's lookup: pending (unaccepted) invitations for a phone.
CREATE INDEX invitation_phone_pending_idx
    ON invitation (phone)
    WHERE phone IS NOT NULL AND accepted_at IS NULL;
