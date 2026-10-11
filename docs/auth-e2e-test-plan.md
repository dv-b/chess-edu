# Auth end-to-end test plan (from a clean database)

Walks every sign-in path on the `feature/telegram-auth-c` branch, starting
from a database that contains only the demo school and one platform admin.

Prerequisites: Docker, Java 21, pnpm, the Telegram bot credentials in
`.env` (`CHESSEDU_TELEGRAM_ENABLED=true`, token, username), and a phone
with Telegram. Ports: API **7373**, web **3100**, Mailpit **8125**.

> One Telegram account can be linked to exactly one user. Your phone
> will be used for the **teacher**. To re-run a Telegram signup with the
> same phone, unlink first (Phase 6).

## Which database

The API reads its datasource from `apps/api/src/main/resources/application-local.yml`.
Two setups are in use; pick the matching `PSQL` once and every SQL step
below works unchanged:

```bash
# A) Docker Postgres from docker-compose.yml (port 5433, user chessedu)
PSQL="docker compose exec -T postgres psql -U chessedu -d chessedu"

# B) Your own local Postgres (what application-local.yml points at today:
#    localhost:5432, database chessedu, your OS user)
PSQL="psql -h localhost -p 5432 -d chessedu"
```

Mailpit, Redis and MinIO still come from Docker either way, so
`docker compose up -d` is needed in both setups.

## Phase 0 — Reset to "platform admin only"

Stop the API if it is running (Ctrl+C in its terminal), then wipe the
database the API uses:

```bash
cd ~/Working/Projects/hypothesis/chesshub/chess-edu-app
# Setup A (Docker Postgres):
docker compose down -v && docker compose up -d
# Setup B (local Postgres):
docker compose up -d && dropdb chessedu && createdb chessedu
```

Start the API (Flyway recreates the schema, the `local` profile seeds the
demo school plus `admin@demo.local` and `student@demo.local`):

```bash
set -a; source .env; set +a
JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-21.0.6/Contents/Home ./gradlew :api:bootRun
```

In a second terminal, once `Started PlatformApplication` appears, turn
the seeded school admin into the platform admin and drop the demo student:

```bash
$PSQL <<'SQL'
UPDATE app_user SET platform_role = 'PLATFORM_ADMIN', display_name = 'Platform Admin'
  WHERE email = 'admin@demo.local';
DELETE FROM app_membership WHERE user_id = (SELECT id FROM app_user WHERE email = 'admin@demo.local');
DELETE FROM app_user WHERE email = 'student@demo.local';
SELECT email, platform_role FROM app_user;
SQL
```

Expected: exactly one row, `admin@demo.local | PLATFORM_ADMIN`. Its
password is still `admin12345`. (If you restart the API later, the seeder
re-creates `student@demo.local`; harmless, delete it again if it bothers
you.)

Start the web app:

```bash
pnpm --filter web dev
```

Open http://localhost:3100/en/auth/login and sign in as the platform
admin. Expected: `/en/admin` with role `PLATFORM_ADMIN` and no school.

## Phase 1 — Platform admin invites the school admin (email)

Signed in as the platform admin, open **Invitations**. Because a
platform admin belongs to no school, the page shows a **School** picker
above the list (Demo Chess School is preselected) and the list is scoped
to it. Press **New invitation**: the form carries the same **School**
field. Fill role **School admin**, email `principal@demo.local`, a name,
language English, Send via Email, and submit.

Expected: redirect back to the list, the new row with Channel `Email`,
status `Pending`.

If anything is rejected, the red box now shows the backend's own reason
under the generic line (for example a missing school, or "Telegram bot is
not configured"), so you are not left guessing.

1. Open Mailpit at http://localhost:8125 → the invitation email → click
   the `/en/auth/invitations/<token>` link.
2. Expected: "You're invited as School admin at Demo Chess School". Set a
   password (8+ chars), submit. Expected: auto-login, `/en/admin`, role
   `SCHOOL_ADMIN`.
3. Log out (Log out button). Sign in again with `principal@demo.local`
   and the password. Expected: `/en/admin`.

Edge cases: open the same invitation link again → "already accepted".
Issue the same email again via curl → HTTP 409.

## Phase 2 — School admin invites a teacher via Telegram (your phone)

Signed in as the school admin:

1. Invitations → **New invitation** → role Teacher, any email (for example
   `teacher.one@demo.local`), name, **Send via: Telegram bot** → Send.
   Expected: no redirect; an "Invitation ready" card with a
   `https://t.me/<bot>?start=INV_…` link and a Copy button. Mailpit gets
   **no** new email.
2. Back to invitations. Expected: the row shows Channel `Telegram`,
   status `Pending`, note "Link not opened yet".
3. Send the link to your phone (paste it into Saved Messages). Tap it →
   the bot greets you with a "Share my phone number" button → tap →
   confirm. Expected: "You're all set" plus a
   `http://localhost:3100/<locale>/auth/accept?ott=…` link.
4. Reload the invitations list. Expected: status `Accepted`, note gone
   (the funnel reached COMPLETED).
5. Log out of the admin. On the Mac, open the accept link from the bot
   (copy it from Telegram Desktop or type it). Expected: a short "Signing
   you in" card, then `/en/admin` as the teacher. No password was ever set.

Edge cases: open the accept link a second time → "This link has
expired". Tap the invitation link again on the phone → "already used".

## Phase 3 — Teacher: account page, username, password, both sign-in methods

Still signed in as the teacher:

1. Press **Account** in the header. Expected: "Telegram is linked",
   a Username card, and a **Set a password** card with no "current
   password" field.
2. Save a username, for example `Teacher.One`. Expected: "Username
   saved" and the field shows `teacher.one`. Try `ab` → validation error.
3. Set a password (twice). Expected: "Password saved" and the card turns
   into **Change password** with a current-password field.
4. Log out. Sign in with `teacher.one` and the password. Expected:
   `/en/admin`. Log out. Sign in with `teacher.one@demo.local` and the
   same password. Expected: same.
5. Log out. Press **Log in with Telegram** → the card asks "Who is
   signing in?". Type the **app** username you saved in step 2
   (`teacher.one`), or the account email, or the phone you shared with
   the bot (`+998…`, separators allowed). This is *not* your Telegram
   @username — the bot never sees it; the server looks up the chat it
   already knows. Press **Send code**. Expected: the card says "Check
   Telegram" and the bot DMs a 6-digit code within a second. Type it →
   **Sign in**. Expected: `/en/admin`. No new tab at any point.
6. Log out. In the bot, send `/login`. Expected: a fresh
   `/auth/accept?ott=` link; opening it signs you in.
7. Account → Change password with a wrong current password → "current
   password is incorrect". With the right one → saved; the old password
   no longer works.

Edge cases: a wrong code → "not right", the field stays; five wrong codes
→ "no longer valid" and Start over; press Send code twice quickly → one
DM only; an unknown username → the card still says "Check Telegram" and
a wrong code says "not right" exactly as for a real account, but nothing
arrives (by design — no account probing); wait 5+ minutes → "no longer
valid". From a Telegram account that never signed up, `/login` → "not
registered yet".

## Phase 4 — School admin invites a parent (email) and the parent adds a child

Sign in as the school admin (`principal@demo.local`):

1. New invitation → role Parent, `parent.one@demo.local`, Send via Email.
   Expected: redirect back to the list, a Mailpit email.
2. Open the link, set a password. Expected: auto-login to `/en/parent`.
3. Add a child (name only). Expected: the child appears on the parent
   dashboard. The child has no login yet; that is roadmap items 4 and 5
   (child switcher, own credentials).
4. Account page from the parent header → set a username → log out →
   sign in with it. Expected: `/en/parent`.

A parent via Telegram works exactly like Phase 2 but needs a **second**
Telegram account, because yours is now the teacher's.

## Phase 5 — Session refresh

Signed in as anyone:

1. DevTools → Application → Cookies → delete `session_access` only.
   Reload. Expected: in the Network tab one `307` to the same URL, then
   the page renders signed in; both cookies are back.
2. Open two tabs, delete `session_access`, reload both at once.
   Expected: both stay signed in.
3. Delete both cookies, reload. Expected: login page.
4. Edit `session_refresh` to garbage, delete `session_access`, reload.
   Expected: login page, both cookies gone.

## Phase 6 — Re-running with the same phone

To sign up again through the bot with your Telegram account:

```bash
$PSQL -c \
  "DELETE FROM telegram_account WHERE telegram_id = (SELECT telegram_id FROM telegram_account ORDER BY linked_at DESC LIMIT 1)"
```

This unlinks the most recently linked account; the user row stays and
can still sign in with its password. Or go back to Phase 0.

## Known gaps you will notice

- "Forgot password?" on the login page is still a dead link (email reset
  is a later roadmap item).
- Students cannot sign in yet (items 4 and 5).
- The teacher lands on `/admin`; there is no teacher shell yet.
