# Kingside — Assistant Instructions

Standing rules for the Claude agent working on this repo.

## Task completion log

Whenever a task from the TaskList is marked `completed`, append a short entry
to the **Completed tasks** section below. Each entry should include:

- The task ID and subject (e.g. `#20 — Homework admin UI: list + CRUD + submissions`).
- A 1–3 line summary of what landed (not a full changelog — just enough that
  a future session can orient quickly).
- The key files / paths touched, so the next agent can jump straight to them
  without re-searching.
- **How to check it as a user** — a short, concrete walkthrough so Bulat
  (or any future reviewer) can verify the feature in the running app without
  reading the code. Cover:
    - Which role(s) to sign in as and where to find a suitable seed account
      (or how to create one).
    - The route / URL to open (e.g. `/<locale>/admin/classes/<id>`).
    - A numbered click-path of the happy flow, plus 1–2 edge cases worth
      eyeballing (permission denied, empty state, validation error, etc.).
    - Any backend state that needs to exist first (e.g. "class must have at
      least one enrolled student" or "seed a session so the dropdown has
      options").

Keep entries in reverse-chronological order (newest first).

---

## Completed tasks

### Auth 1 — Silent session refresh in the web app

First item of the auth roadmap agreed with Bulat on 2026-10-09 (see
`docs/telegram-auth-handoff.md` → "Auth roadmap decisions"). The API has
issued a 14-day rotating refresh token since M2, but nothing in the web
app ever called `/auth/refresh`, so every session died when the 30-minute
access cookie expired. Now a session lasts as long as the user comes back
within 14 days.

Files:

- `apps/web/src/middleware.ts` — wraps the next-intl middleware. If the
  `session_access` cookie is absent but `session_refresh` is present, it
  POSTs `/api/v1/auth/refresh` (forwarding User-Agent / X-Forwarded-For
  for the refresh-token row) and answers **307 to the same URL** with the
  new cookie pair, so the re-request renders with a valid session.
  On failure it clears both cookies and lets the shell layouts bounce to
  `/auth/login` as before. Why a self-redirect: Server Components read
  cookies from the incoming request, and forwarding a rewritten cookie
  header through next-intl's response would depend on Next internals.
  **Single-flight guard** (CAREFUL): the backend's rotation treats a
  re-presented refresh token as theft and revokes the family; a page load
  fans out into parallel RSC requests that would all refresh with the
  same old token. An in-memory `Map<token, Promise>` with a 15 s window
  hands every concurrent caller the same result. Single web instance
  (same assumption as the bot worker); multi-instance needs a reuse grace
  window on the backend instead.
- `apps/web/src/lib/session-cookies.ts` — new, dependency-free: cookie
  names, lifetimes, `sessionCookieOptions`, and `accessCookieMaxAge(expiresIn)`
  = backend `expiresIn` minus a 60 s safety margin, so the cookie always
  dies *before* the JWT inside it (otherwise there's a window where the
  cookie exists but every API call 401s → logout instead of refresh).
- `apps/web/src/lib/session.ts` — imports the shared constants;
  `setSession` takes optional `expiresIn`.
- `auth/login/actions.ts`, `auth/accept/actions.ts`,
  `auth/invitations/[token]/actions.ts` — pass `expiresIn` to `setSession`.

Verified with curl against the running stack: refresh-only cookie →
307 + both Set-Cookie (access Max-Age 1740); three concurrent requests
with the same old token → all 307 with the *same* new refresh token;
following with the new cookies → 200; the newest refresh still rotates at
the API (family intact); a garbage refresh → both cookies cleared +
redirect to `/en/auth/login`. `npx tsc --noEmit` clean. i18n untouched
(946). No backend change.

Deliberately scoped out:

- **Backend reuse grace window** (accept the just-rotated token for ~30 s
  and return the successor) — needed only for multi-instance web.
- **Refreshing on a mid-request 401** — the 60 s cookie margin makes that
  window practically unreachable; the 18 existing `session/logout` 401
  handlers are unchanged.
- **Idle timeout / "remember me"** — the refresh token is 14 d from each
  rotation, so an active user stays in indefinitely; add an absolute cap
  if product wants one.

How to check as a user:

1. Sign in at `/en/auth/login` (`admin@demo.local / admin12345`).
2. In DevTools → Application → Cookies, delete only `session_access`
   (keep `session_refresh`). Reload any admin page.
   Expected: one 307 to the same URL in the Network tab, then the page
   renders logged-in; both cookies are back with fresh expiries.
3. Delete both cookies, reload → login page (unchanged behaviour).
4. Edge: edit `session_refresh` to garbage, delete `session_access`,
   reload → login page, and both cookies are gone.
5. Edge: open two tabs, delete `session_access`, reload both at once →
   both stay logged in (single-flight; without it the second tab would
   have revoked the family and logged both out).

### Telegram Auth D — `/auth/accept` page + admin channel picker & funnel state

Fourth slice of the Telegram-auth umbrella: the frontend, plus the small
backend piece it needed. The bot's deeplink now lands on a real page
that signs the user in, and school admins can issue Telegram
invitations (and watch the bot funnel) from the existing invitations
screens. Login-Widget button still deferred (needs a prod domain).

Backend (invitation issue/list, no schema change — V012 already had
the columns):

- `apps/api/.../identity/invitation/dto/IssueInvitationRequest.java` —
  optional `deliveryChannel` (`@Pattern EMAIL|TELEGRAM`, null = EMAIL).
- `apps/api/.../identity/invitation/dto/InvitationView.java` — adds
  `deliveryChannel`, `botState` (null for EMAIL), and `botDeeplink`
  (`https://t.me/<bot>?start=INV_<token>`; **only in the issue response
  of a TELEGRAM invite, never in list views** — the raw token isn't
  stored, so it can't be re-shown).
- `apps/api/.../identity/invitation/InvitationService.java` — injects
  `TelegramProperties`; on `TELEGRAM`: 400 if `bot-username` is blank,
  sets `delivery_channel=TELEGRAM` + `bot_state=PENDING`, **skips the
  email** and returns the deeplink via a new `toView(…, botDeeplink)`
  overload. EMAIL path unchanged. (Student invites were already 403 on
  every channel via `enforceCallerAuthority` — no extra guard.)
- `apps/api/src/test/.../invitation/TelegramInvitationIssueIntegrationTest.java`
  — 4 tests (pins `chessedu.telegram.bot-username` via
  `@SpringBootTest(properties=…)`): TELEGRAM issue → deeplink whose
  token hashes to the row, `PENDING`, list shows channel+state but no
  deeplink; EMAIL default unchanged; STUDENT+TELEGRAM 403; bogus
  channel 400.

Frontend:

- `apps/web/src/app/[locale]/auth/accept/{page,accept-form,actions}.tsx|ts`
  — new. Page reads `?ott=`; missing → error card. Otherwise a client
  form with hidden `ott` **auto-submits once on mount** (ref-guarded —
  the OTT is single-use, so a strict-mode double effect must not fire
  twice) to `acceptTelegramAction`: `POST /auth/telegram/accept` →
  `setSession` cookies → `/auth/me` → `homeForRole` redirect
  (parent/student/admin), identical tail to the password login. 401/400
  → "expired" state (ask the bot `/start` again, or email/password
  login); network/unknown → retry button. It stays a real `<form>` so a
  blocked auto-submit leaves a working button.
- `apps/web/src/lib/home-for-role.ts` — `homeForRole` extracted from
  `auth/login/actions.ts` (a `"use server"` module can't export a sync
  helper) and shared by both flows.
- `apps/web/src/app/[locale]/admin/invitations/new/invite-form.tsx` +
  `actions.ts` — "Send via" select (Email / Telegram bot) with a hint;
  `deliveryChannel` validated + posted. On a TELEGRAM success the
  action **returns `{issued:{email,deeplink}}` instead of redirecting**
  (deliberately not round-tripping the token through a URL query); the
  form swaps to an "Invitation ready" card with a read-only link input,
  Copy button (clipboard API, graceful no-op if unavailable) and
  back-to-list. EMAIL success redirects as before.
- `apps/web/src/app/[locale]/admin/invitations/page.tsx` — new
  "Channel" column; under the status pill a muted `.pill-note` with the
  bot funnel step (`botState.*`) while a TELEGRAM invite is pending, or
  if it was `CANCELLED` in the bot. `funnelOf()` helper.
- `apps/web/src/app/globals.css` — `.pill-note` (small, muted, normal
  case) next to the `.pill` block.
- i18n: `auth.telegramAccept.*` (11 keys) and
  `admin.invitations.{table.channel, channel.*, botState.*(5),
  new.{channelLabel,channelHint,channel.*,issuedTitle,issuedTelegram,
  deeplinkLabel,copy,copied,backToList}}` in en/ru/uz. **en=ru=uz=946**
  leaf keys (was 917).

Build verify: `./gradlew :api:compileJava :api:compileTestJava` clean;
`:api:test --tests '*TelegramInvitationIssue*' --tests '*InvitationFlow*'
--tests '*TelegramAccept*' --tests '*AuthFlow*'` all green (15 tests).
`npx tsc --noEmit` clean. CSS braces balanced. Walked through in the
browser against the running stack (accept page happy + used-token
paths, Telegram invite issue → deeplink card, list columns).

Deliberately scoped out:

- **Login-Widget button** on `/auth/login` + its endpoint — domain-ready
  phase.
- **Re-showing a Telegram deeplink** — the raw token isn't stored, so
  an admin who loses it must revoke + re-issue (same as email).
- **Distinct "bot not configured" error in the admin form** — the
  backend returns 400 with a clear message, but the form maps every
  400 to the generic `invalid` copy. Fine for v1 (it's an operator
  misconfiguration, and the backend log says exactly what's missing).
- **Mock-Telegram end-to-end test** (bot `/start` → contact →
  deeplink → accept page) — Task E.

How to check as a user:

Needs Postgres + backend (Java 21, `.env` sourced if you want the real
bot) + web (`pnpm --filter web dev`, port 3100). API is on **7373**.

1. Sign in as `admin@demo.local / admin12345` → `/<locale>/admin/invitations`
   → "New invitation". Fill an email + name, role Teacher or Parent,
   **Send via: Telegram bot** → submit.
   Expected: no redirect — an "Invitation ready" card with a
   `https://t.me/<bot>?start=INV_…` link, a working Copy button, and
   "Back to invitations". (With `CHESSEDU_TELEGRAM_BOT_USERNAME` unset
   you get the generic "check the form" error instead — set it in
   `.env`.) Mailpit (`localhost:8125`) gets **no** new message.
2. "Back to invitations": the new row shows Channel = Telegram, status
   Pending, and "Link not opened yet" under the pill. Email rows show
   Channel = Email and nothing extra.
3. Send the link to yourself in Telegram and tap through the bot (see
   Telegram Auth B's walkthrough). After the contact share, the list
   row's note becomes "Signed up via bot" on refresh; the bot's reply
   contains `http://localhost:3100/<locale>/auth/accept?ott=…`.
4. Open that link in the browser where the web app runs.
   Expected: a brief "Signing you in…" card, then a redirect to
   `/<locale>/admin` (teacher/admin) or `/<locale>/parent` (parent),
   logged in — no password ever set.
5. Edge cases worth eyeballing:
   - Open the same accept link again → "This link has expired" card
     with "Back to login". Same for `/auth/accept?ott=garbage`.
   - `/auth/accept` with no `ott` → the "link is incomplete" card.
   - Without the bot: mint an OTT by SQL (see Telegram Auth C, step 2)
     and open `/en/auth/accept?ott=<raw>` — same happy path.
   - Issue a Telegram invite as a Parent role, tap through, and confirm
     the accept redirect lands on `/parent`, not `/admin`.
   - `/cancel` in the bot mid-flow → list note "Cancelled in the bot".

### Telegram Auth C — OTT redemption endpoint (`POST /api/v1/auth/telegram/accept`)

Third slice of the Telegram-auth umbrella: the web-side half of the
bot → web handoff. The `/<locale>/auth/accept?ott=<token>` deeplink the
bot sends (Task B) now has an endpoint to redeem against: the raw OTT
goes in, a `TokenResponse` (access + refresh JWT pair, same shape as
`/auth/login` and `/auth/invitations/accept`) comes out. Permit-all,
no bot token needed — the redemption path runs on every instance even
with the bot worker off. The Login-Widget endpoint
(`POST /auth/telegram/login`, HMAC) stays deferred until a prod domain
exists for `@BotFather /setdomain`.

Also: **Telegram A + B are now compile- and boot-verified** (this slice
was built locally with Gradle + Java 21 + Testcontainers; Flyway V012
applies, Hibernate `validate` passes). The "not compile-verified"
caveat from the handoff is closed.

Files:

- `apps/api/.../identity/auth/AuthService.java` — new public
  `issueSessionFor(User, ip, userAgent)`: ACTIVE gate, `lastLoginAt`
  stamp, membership lookup, fresh refresh-token family → `mintTokens`.
  This is the "session for an externally-verified identity" primitive
  the Login Widget will reuse; it avoids a third copy of the ~12-line
  mint block (`AuthService.login` and `InvitationService.accept` each
  have their own).
- `apps/api/.../identity/telegram/TelegramAuthService.java` — new,
  **unconditional** `@Service`. `accept(rawOtt, ip, ua)` =
  `TelegramAuthTokenService.redeem` → bump `telegram_account.last_seen_at`
  → `AuthService.issueSessionFor`. One `@Transactional` so a failed
  issuance (user suspended after the bot minted the OTT) rolls back the
  used-stamp — no used-but-no-session rows.
- `apps/api/.../identity/telegram/TelegramAuthController.java` — new,
  `@RequestMapping("/api/v1/auth/telegram")`, `POST /accept` with
  `@Valid TelegramAcceptRequest` + `HttpServletRequest` for IP /
  User-Agent (same extraction as `AuthController`).
- `apps/api/.../identity/telegram/dto/TelegramAcceptRequest.java` —
  `record(@NotBlank @Size(max=200) String ott)`.
- `apps/api/.../security/SecurityConfig.java` — `/api/v1/auth/telegram/**`
  added to the permit-all list (covers the future `/login` too).
- `apps/api/src/test/.../identity/telegram/TelegramAcceptFlowIntegrationTest.java`
  — 5 MockMvc tests: happy path (accept → `/me` shows TEACHER @ Demo
  Chess School → refresh rotates → OTT single-use → `used_at`,
  `last_login_at`, `last_seen_at` all stamped), unknown OTT 401, blank
  OTT 400, expired OTT 401 (not stamped used), SUSPENDED user 401
  (used-stamp rolled back). Seeds a password-less user + membership +
  `telegram_account` directly; mints the OTT through
  `TelegramAuthTokenService` exactly as the bot does.

Error semantics: every token failure and the non-ACTIVE user return
`401 UNAUTHENTICATED` with an opaque "Invalid token"/"Invalid
credentials" message — no unknown/used/expired distinction, matching
`AuthService.bad()`. Validation (blank/oversized `ott`) is the usual
`400 VALIDATION_FAILED`.

Build verify: `./gradlew :api:compileJava :api:compileTestJava` clean;
`./gradlew :api:test --tests '*TelegramAcceptFlowIntegrationTest' --tests
'*AuthFlowIntegrationTest'` green (Testcontainers Postgres, Docker
required). Note the machine's default `java` is 1.8 — run Gradle with
`JAVA_HOME` pointed at a 21 JDK (e.g. Corretto 21 under
`~/Library/Java/JavaVirtualMachines/`). i18n untouched (en=ru=uz=917).

Deliberately scoped out:

- **Frontend** `/<locale>/auth/accept` page that reads `?ott=`, POSTs
  it, stores the tokens the way `/auth/login` does, and redirects by
  role — Task D. Until then the deeplink lands on a 404 in the web app;
  verify with curl (below).
- **Login-Widget endpoint** (`POST /auth/telegram/login`, HMAC against
  the bot token, mints an OTT internally and calls the same
  `TelegramAuthService` path) — domain-ready phase.
- **Rate limiting** on `/auth/telegram/accept` — the OTT is 2×UUID
  (~244 bits) with a 10-min TTL, so brute force isn't practical; the
  endpoint inherits whatever rate limiting `/auth/login` gets later.
- **Bot-level mock integration test** (mock Telegram HTTP server →
  `/start` → contact → deeplink → accept) — Task E; this slice tests
  from the minted OTT onward.

How to check as a user:

No bot token needed. Postgres + backend running (`docker compose up -d`,
then `./gradlew :api:bootRun` with Java 21).

1. Pick (or seed) an ACTIVE non-student user. The demo admin works:
   ```sql
   SELECT id FROM app_user WHERE email = 'admin@demo.local';
   ```
2. Mint an OTT the way the bot does. Simplest without the bot: run the
   `TelegramAuthTokenService.mint(userId)` path by inserting a row
   whose `token_hash` is the SHA-256 of a raw token you choose:
   ```sql
   INSERT INTO telegram_auth_token (id, user_id, token_hash, issued_at, expires_at)
   VALUES (gen_random_uuid(), '<user id>',
           encode(sha256('my-raw-ott'::bytea), 'hex'),
           now(), now() + interval '10 minutes');
   ```
   (`TokenHasher.hash` is exactly hex-encoded SHA-256, so this matches
   what `TelegramAuthTokenService.mint` would store.)
3. Redeem it:
   ```bash
   curl -s -X POST http://localhost:8080/api/v1/auth/telegram/accept \
     -H 'Content-Type: application/json' -d '{"ott":"my-raw-ott"}'
   ```
   Expected: `200` with `accessToken` / `refreshToken` / `tokenType:
   Bearer` / `expiresIn`. Then `curl -H "Authorization: Bearer <access>"
   http://localhost:8080/api/v1/auth/me` returns the user with their
   role and school.
4. Edge cases worth eyeballing:
   - Repeat step 3 with the same OTT → `401 UNAUTHENTICATED`
     (single-use); `SELECT used_at FROM telegram_auth_token` is set.
   - `{"ott":"garbage"}` → `401`; `{"ott":""}` → `400 VALIDATION_FAILED`.
   - `UPDATE telegram_auth_token SET expires_at = now() - interval '1
     minute'` on a fresh row → `401`, and `used_at` stays NULL.
   - `UPDATE app_user SET status = 'SUSPENDED'` on the user, fresh OTT
     → `401`, and `used_at` stays NULL (rolled back). Set the user back
     to `ACTIVE` afterwards.
   - With the bot enabled (see Telegram Auth B's walkthrough), the
     deeplink the bot sends now redeems end-to-end via curl of the
     `ott` query param.

### Telegram Auth B — Bot worker (RestClient long-poll + signup funnel)

Second slice of the Telegram-auth umbrella. Adds the bot worker that
drives the invitation-only signup conversation end-to-end:
`/start INV_<token>` → "Share Contact" (Telegram-verified phone) →
account created → an `/auth/accept?ott=<token>` deeplink back to the
web app. No web endpoint yet — the OTT redemption that turns the
deeplink into a JWT is Task C. The Login-Widget repeat-sign-in path
is Task C/D and a prod domain.

Locked decisions reflected in the code (all confirmed with Bulat):

- **Spring RestClient** (not the telegrambots library, not raw
  `java.net.http`) — already on the classpath, auto-Jackson, no new
  dependency.
- **Long-polling** via `@Scheduled(fixedDelay)` — one `getUpdates`
  per cycle, zero infra, localhost-friendly. `@EnableScheduling`
  added to `PlatformApplication`.
- **Master switch off by default** — the bot-facing beans are
  `@ConditionalOnProperty(chessedu.telegram.enabled=true)`, so normal
  dev boots, the test profile, and CI never poll Telegram. The OTT
  primitive is unconditional (Task C's redeem path needs it without
  the bot running).
- **In-memory signup↔contact correlation** — a "Share Contact"
  message carries no invite token, so a `ConcurrentHashMap<telegramId,
  invitationId>` remembers who's mid-signup. `bot_state` is persisted
  only for the admin funnel display. Single-instance v1; multi-instance
  would move the map + poll offset to Redis (scope-out).

Files:

- `apps/api/.../identity/telegram/TelegramProperties.java` — `@ConfigurationProperties("chessedu.telegram")`
  record: `enabled`, `botToken`, `botUsername`, `apiBaseUrl`
  (overridable so Task E's mock server can intercept),
  `pollTimeoutSeconds` (default 50), `ottTtl` (default PT10M). Helper
  `botApiPrefix()` = `apiBaseUrl + "/bot" + botToken`.
- `apps/api/.../identity/telegram/dto/{TgUpdate,TgMessage,TgUser,TgChat,TgContact,TgResponse}.java`
  — Jackson `@JsonIgnoreProperties(ignoreUnknown=true)` records for
  the Bot API wire shapes. `TgResponse<T>` is the `{ok, result, …}`
  envelope.
- `apps/api/.../identity/telegram/TelegramApiClient.java` —
  `@ConditionalOnProperty` RestClient wrapper. `getUpdates(offset)`
  (long-poll; read timeout = pollTimeout + 15s so a full-duration
  hold doesn't trip a socket timeout), `sendMessage` (removes the
  keyboard), `sendContactRequest` (one-time ReplyKeyboardMarkup with
  `request_contact:true`). Outbound bodies are `Map<String,Object>`;
  send failures log and don't abort the loop.
- `apps/api/.../identity/telegram/TelegramAuthTokenService.java` —
  **unconditional** `@Service`. `mint(userId)` returns a raw OTT
  (stores SHA-256 hash via `TokenHasher`, TTL from props);
  `redeem(rawToken)` validates usable + stamps used + returns the
  `User`, throwing `INVALID_TOKEN` on any failure (opaque — no
  unknown/used/expired distinction). Task C's controller will call
  `redeem`.
- `apps/api/.../identity/telegram/TelegramBotMessages.java` —
  locale-aware bot copy (uz default / ru / en) keyed off the Telegram
  `language_code`. Static helper rather than MessageSource bundles —
  small fixed string set, uz/ru/en audience. `resolveLocale()` maps
  `language_code` → product locale.
- `apps/api/.../identity/telegram/TelegramSignupService.java` —
  `@ConditionalOnProperty`. `handleStart` (validates the invitation:
  TELEGRAM channel, not accepted/expired, non-STUDENT role; sets
  `bot_state=AWAITING_CONTACT`; asks for contact),
  `handleContact` (validates the shared contact is the sender's own,
  normalises phone to E.164, creates `User` [email+displayName+role
  from the invitation, **no Credentials** — Telegram users have no
  password] + `Membership`/platformRole + `TelegramAccount` [profile
  snapshot + phone + chat_id], redeems the invitation,
  `bot_state=COMPLETED`, mints OTT, replies with the
  `/<locale>/auth/accept?ott=…` deeplink), `handleCancel`
  (`bot_state=CANCELLED`), `handleHelp` (fallback). Mirrors
  `InvitationService.accept` minus credentials. `normalizePhone`
  guards against the DB E.164 CHECK so a malformed number fails
  gracefully instead of 500ing.
- `apps/api/.../identity/telegram/TelegramBotWorker.java` —
  `@ConditionalOnProperty` + `@Scheduled(fixedDelayString=
  "${chessedu.telegram.poll-delay-ms:1000}")`. Per-update try/catch
  with `offset` always advancing past a poison update; per-cycle
  catch + 2s backoff on network blips. In-memory `AtomicLong` offset
  (race-free under `fixedDelay`'s no-overlap guarantee).
- `apps/api/.../PlatformApplication.java` — added `@EnableScheduling`
  + `TelegramProperties.class` to `@EnableConfigurationProperties`.
- `apps/api/src/main/resources/application.yml` — `chessedu.telegram.*`
  block (all env-overridable, `enabled` defaults false).
- `.env.example` — documented the five `CHESSEDU_TELEGRAM_*` vars
  with a "keep the token secret" note.

Build verify: backend can't compile in the sandbox (no Gradle /
javac / internet). Brace + paren balance verified on every new file;
all repo/entity method references checked against the real sources
(`UserRepository.findByEmail`, `Membership` setters,
`TelegramAccountRepository.existsByTelegramId`, `InvitationRepository.findByTokenHash`).
RestClient + `@Scheduled` + `@ConditionalOnProperty` are all stock
Spring Boot 3.3. `PlatformApplication` already uses
`@ConfigurationPropertiesScan`, so `TelegramProperties` is picked up
automatically — only `@EnableScheduling` was added. The yaml block
uses kebab-case (`bot-token`, `ott-ttl`) per the existing
`chessedu.*` convention; relaxed binding maps it to the record's
camelCase. OTT redemption uses `ErrorCode.UNAUTHENTICATED` (there's
no `INVALID_TOKEN` code). Verify locally with `./gradlew :api:compileJava`.
i18n untouched (en=ru=uz=917).

Deliberately scoped out:

- **Auth endpoints** (`/api/v1/auth/telegram/accept` OTT redemption;
  `/auth/telegram/login` widget HMAC) — Task C. `TelegramAuthTokenService.redeem`
  is ready for the accept controller to call.
- **Frontend** (`/auth/accept` page, invitation channel picker +
  funnel state, widget button) — Task D.
- **Integration test + dev runbook + env docs** — Task E (the test
  will point `apiBaseUrl` at a mock HTTP server).
- **Multi-instance** — in-memory offset + correlation map are
  single-instance. Redis-backed or webhook conversion is a later
  task.
- **Enabling without a token** — if `enabled=true` but `botToken` is
  blank, the client calls `/bot/getUpdates` (no token) and logs
  warnings; an operator error that's loud in logs, not guarded.
- **Bot-reply MessageSource bundles** — copy is inline in
  `TelegramBotMessages`; promote to `messages_*.properties` if it
  grows.

How to check as a user:

Requires a real bot token, so this can't be exercised until you set
one. Once `@BotFather` gives you a token + username:

1. Export the env (or put in `.env`): `CHESSEDU_TELEGRAM_ENABLED=true`,
   `CHESSEDU_TELEGRAM_BOT_TOKEN=<token>`,
   `CHESSEDU_TELEGRAM_BOT_USERNAME=<username>`. Start Postgres + the
   backend. Watch the log — with `enabled=true` you should see the
   poll loop running (no errors if the token is valid); with a bad
   token you'll see repeated `getUpdates returned not-ok` warnings.
2. Issue a TELEGRAM-channel invitation. There's no admin UI for the
   channel picker yet (Task D), so set it directly for now:
   ```sql
   UPDATE invitation SET delivery_channel = 'TELEGRAM', bot_state = 'PENDING'
     WHERE email = '<the invitee email>' AND accepted_at IS NULL;
   ```
   Grab the raw token from the issue flow (it's only shown once at
   issue time — re-issue if needed) — call it `<RAW>`.
3. In Telegram, open `https://t.me/<botusername>?start=INV_<RAW>` (or
   DM the bot `/start INV_<RAW>`). The bot replies with a welcome +
   a "📱 Share my phone number" button (in your Telegram app
   language — uz/ru/en).
4. Tap the button. Telegram sends your verified number. The bot
   replies "You're all set! Tap to finish signing in:" + an
   `/<locale>/auth/accept?ott=…` link. (The page that consumes it is
   Task D; for now confirm the row landed:)
   ```sql
   SELECT u.email, ta.telegram_id, ta.phone, i.bot_state
   FROM telegram_account ta
   JOIN app_user u ON u.id = ta.user_id
   JOIN invitation i ON i.accepted_user = u.id;
   ```
   Expected: the new user, the verified phone in E.164, and
   `bot_state = COMPLETED`. A matching `telegram_auth_token` row
   exists (unused, ~10 min TTL).
5. Edge cases worth eyeballing:
   - `/start` with a garbage/expired/already-accepted token, or an
     EMAIL-channel token → distinct polite error replies, no row
     created.
   - Sharing someone else's contact card (forward a contact) → "share
     your own number" reply, no row.
   - `/cancel` mid-flow → `bot_state = CANCELLED`, conversation
     resets. `/cancel` with nothing pending → "nothing to cancel".
   - `/start` from a Telegram account already linked → "already
     linked, sign in on the website".
   - Restart the backend mid-conversation (after `/start`, before the
     contact tap) → the in-memory correlation is lost; the contact
     tap replies "start with your invite link first". Re-run `/start`.

### Telegram Auth A — V012 schema + entities + repos + invitation extension

First slice of the Telegram-auth umbrella. Adds the persistence layer
for two intertwined flows decided with Bulat: bot-driven initial
signup (captures a Telegram-verified phone via the native "Share
Contact" button) and web Login-Widget repeat sign-in. Both funnel
through one `telegram_auth_token` one-time-token table for the final
bot/widget→web handoff, so the web side has a single redemption path
regardless of which flow the user came from. Telegram-based auth is
for non-STUDENT roles only (admins, teachers, parents); signup is
invitation-only (no self-signup). No service / controller / bot
worker in this slice — those are Tasks B (bot worker) / C (auth
endpoints) / D (frontend) / E (test + runbook).

Locked design decisions reflected in the schema:

- **`telegram_account` is a 1:1 side table on `app_user`**, not
  columns on the core identity row. Most users (every student, plus
  staff who only use email/password) never link Telegram, so the
  columns would be mostly NULL; and the Telegram profile snapshot
  (username, photo, language) doesn't belong on `app_user`. Mirrors
  the "credentials in their own table" decision from V002. `UNIQUE`
  on both `user_id` (one link per user — the single-trust-path rule)
  and `telegram_id` (one user per Telegram identity).
- **All lookups key on `telegram_id`** (the stable Telegram-side
  BIGINT primary key), never on `username` — usernames can be changed
  or recycled. `username` / `first_name` / `photo_url` are mutable
  fields we re-snapshot on each interaction.
- **Two flows, one redemption path.** Bot signup and Login-Widget
  repeat sign-in both end at `/auth/accept?ott=<token>`. Decided with
  Bulat: RestClient long-poll bot worker (Task B), email/password
  form stays visible alongside the widget, build signup flow first
  and defer the widget endpoint until a prod domain exists for
  `@BotFather /setdomain`.

Files:

- `apps/api/src/main/resources/db/migration/V012__telegram_auth.sql`
  — three changes: (1) `telegram_account` table (1:1 on app_user,
  `telegram_id` BIGINT unique, profile snapshot, E.164 `phone` with a
  CHECK regex `^\+[1-9][0-9]{6,15}$`, `chat_id` for bot DMs,
  domain-named `linked_at` / `last_seen_at` timestamps); (2)
  `telegram_auth_token` table mirroring `password_reset_token`
  line-for-line (SHA-256 hex `token_hash` unique, `issued_at` /
  `expires_at` / `used_at`, single-use, short TTL); (3) `invitation`
  extension — `delivery_channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL'`
  (so every existing row keeps current behaviour) + nullable
  `bot_state VARCHAR(24)`, both CHECK-constrained
  (`EMAIL`/`TELEGRAM`; `PENDING`/`STARTED`/`AWAITING_CONTACT`/
  `COMPLETED`/`CANCELLED`).
- `apps/api/src/main/java/com/chessedu/platform/identity/model/TelegramAccount.java`
  — JPA entity. Explicit UUID `@Id` (no `@GeneratedValue`),
  `long telegramId`, `Long chatId` (nullable). Deliberately does NOT
  use `AuditingEntityListener`: its timestamps are domain-named
  (`linkedAt` / `lastSeenAt`) and set explicitly by the service,
  matching the `RefreshToken` / `PasswordResetToken` convention in
  this same identity domain rather than the `@CreatedDate` /
  `@LastModifiedDate` listener used by `User`. (The generic handoff
  said "AuditingEntityListener for timestamps"; the local identity-
  domain convention for token/side tables is the better fit and is
  noted here so the next agent doesn't "correct" it.)
- `apps/api/src/main/java/com/chessedu/platform/identity/model/TelegramAuthToken.java`
  — entity mirroring `PasswordResetToken` (token_hash unique,
  `isUsed()` / `isExpired(now)` / `isUsable(now)` helpers).
- `apps/api/src/main/java/com/chessedu/platform/identity/model/DeliveryChannel.java`
  — enum `EMAIL` / `TELEGRAM`.
- `apps/api/src/main/java/com/chessedu/platform/identity/model/BotState.java`
  — enum `PENDING` / `STARTED` / `AWAITING_CONTACT` / `COMPLETED` /
  `CANCELLED` with the funnel documented in the javadoc.
- `apps/api/src/main/java/com/chessedu/platform/identity/repo/TelegramAccountRepository.java`
  — `findByTelegramId(long)`, `findByUserId(UUID)`,
  `existsByTelegramId(long)`.
- `apps/api/src/main/java/com/chessedu/platform/identity/repo/TelegramAuthTokenRepository.java`
  — `findByTokenHash(String)`.
- `apps/api/src/main/java/com/chessedu/platform/identity/model/Invitation.java`
  — added `deliveryChannel` (`@Enumerated(STRING)` +
  `@JdbcTypeCode(VARCHAR)`, defaults to `DeliveryChannel.EMAIL` so
  the existing `issue()` path — which doesn't set it — persists EMAIL)
  and `botState` (nullable) fields + getters/setters. No constructor
  to break: `InvitationService.issue()` builds via no-arg constructor
  + setters, so the new fields are additive.

Build verify: backend can't compile in the sandbox (no Gradle
wrapper / javac / internet). Entities follow the existing identity
patterns line-for-line (User enum mapping, PasswordResetToken token
shape, RefreshToken manual-timestamp convention), so the compile
should be clean. Verify locally with `./gradlew :api:compileJava`
before building Task B. i18n untouched (en=ru=uz=917, parity holds).

Deliberately scoped out:

- **Bot worker** (`/start` / contact-share / `/cancel` handlers,
  RestClient long-poll loop) — Task B.
- **Auth endpoints** (`/api/v1/auth/telegram/accept` for OTT
  redemption; `/api/v1/auth/telegram/login` for the HMAC-verified
  Login-Widget callback) — Task C. `/login` deferred until a prod
  domain exists for `@BotFather /setdomain`.
- **Frontend** (`/auth/accept` OTT page, invitation channel picker +
  funnel state in the admin list, Login Widget button) — Task D.
- **Integration test + dev runbook + env docs** — Task E.
- **DTO surface for `telegram_account` / `bot_state`** — added when C
  / D need them. This slice stops at "the persistence layer exists",
  matching the #49 / #65 first-slice pattern.

How to check as a user:

No UI / API yet — verification is schema-level.

1. Start Postgres (`docker compose up -d postgres`) and the backend
   (`./gradlew :api:bootRun`). Watch the Flyway output on boot for
   `Migrating schema "public" to version "12 - telegram auth"`
   followed by `Successfully applied 1 migration`.
2. Connect to the DB and eyeball the new tables:
   ```sql
   \d telegram_account
   \d telegram_auth_token
   \d invitation
   ```
   Expected: `telegram_account` has `telegram_id BIGINT` with a unique
   index, `user_id` unique, the E.164 phone CHECK, and
   `linked_at` / `last_seen_at`. `telegram_auth_token` mirrors
   `password_reset_token`. `invitation` now carries
   `delivery_channel` (default `EMAIL`) + `bot_state`.
3. Confirm existing invitations defaulted correctly:
   ```sql
   SELECT delivery_channel, bot_state FROM invitation LIMIT 5;
   ```
   Expected: every existing row reads `EMAIL` / NULL.
4. Verify the CHECK constraints fire:
   ```sql
   INSERT INTO telegram_account (user_id, telegram_id, phone)
     VALUES (gen_random_uuid(), 1, '12345');                          -- bad phone (no +)
   ```
   → the insert should fail with `telegram_account_phone_e164_chk`
   (and also the FK on a random user_id). A valid phone
   (`'+998901234567'`) passes the regex.
5. Smoke-insert a valid pair against a real staff user, then roll
   back:
   ```sql
   INSERT INTO telegram_account (user_id, telegram_id, username, phone, chat_id)
   SELECT u.id, 123456789, 'demo_admin', '+998901234567', 123456789
   FROM app_user u WHERE u.email = 'admin@demo.local';
   SELECT telegram_id, phone, linked_at FROM telegram_account
     WHERE telegram_id = 123456789;
   DELETE FROM telegram_account WHERE telegram_id = 123456789;
   ```
6. Confirm Flyway history:
   ```sql
   SELECT version, description, success FROM flyway_schema_history
     ORDER BY installed_rank DESC LIMIT 2;
   ```
   Expected: row 12 / `telegram auth` / `true`.
7. Restart the backend — Hibernate's schema validation should map
   `TelegramAccount` / `TelegramAuthToken` / the extended `Invitation`
   cleanly; a column/type mismatch would refuse startup with a clear
   pointer.

### #73 — Student cross-class questions rollup (B4)

Lane-B slice. Adds a backend rollup endpoint + a new
`/student/questions` route + a sidebar entry so a student can see
every Q&A thread they've opened across every class in one list.
Pre-#73 the only way to find an old conversation was to revisit
the homework / lesson detail page individually.

What the rollup does:

- **Backend `GET /api/v1/me/questions`** — returns every thread
  (open + resolved) the calling student has opened, newest first.
  Each row carries the question head + parent title + ordered
  reply chain so the UI can render a preview without per-row
  fetches. Threads on archived parents stay visible (the
  historical record matters to the asker even after the teacher
  cleans up the homework).
- **Frontend `/student/questions` route** — server-rendered list
  of compact rows. Each row shows: parent kind + title + block
  index, status pill (Open / Resolved), question body snippet
  (≤160 chars), asked-at relative time, reply count, last-reply
  meta. Click → deep-links to the parent's detail page where the
  inline per-block QA panel (Task #69) handles reply / resolve.
  Read-only — no write actions on this surface.
- **Sidebar entry** — a new live "Questions" link slots between
  Lessons and Puzzles under the LEARN group. Pathname-driven
  active state highlights it on `/student/questions`. No count
  badge in v1 (would need a "last read at" per-thread per-student
  signal we don't track yet).

Three deliberate scope-outs:

- **No count badge on the sidebar.** Showing "N unread replies"
  needs a `last_read_at` column per thread per student to
  compare against the latest reply timestamp. Adding that's its
  own slice. v1 just shows the entry without a count; the
  rollup itself is the discovery surface.
- **Sort by question created_at, not by last activity.** Sorting
  by activity (max(reply.createdAt) per thread) is more useful
  ("where's the new reply?") but requires an extra GROUP BY in
  SQL. v1 sorts by `block_question.created_at DESC` — most
  recently asked first. Activity-based ordering can land later
  with the unread-tracking work.
- **No status filter.** Shows all threads (open + resolved)
  inline, with the pill differentiating. A "show resolved only"
  toggle would be premature — the list is small enough to scan.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/repo/BlockQuestionRepository.java`
  — added `findByStudentUserIdOrderByCreatedAtDesc(UUID)` derived
  query. No new index needed; the existing
  `block_question_student_block_idx (parent_kind, parent_id,
  block_index, student_user_id)` doesn't help here because
  student_user_id isn't a leading column. Postgres falls back to
  a sequential scan, which is fine — a student's per-class
  thread count is typically <20 across the term, so the rollup
  query stays cheap. If real-world classes ever exceed that, we
  can add a dedicated `(student_user_id, created_at DESC)`
  index.
- `apps/api/src/main/java/com/chessedu/platform/classes/BlockQuestionService.java`
  — added `listAllMyThreads(principal)` returning
  `List<BlockQuestionThreadView>`. Reuses the existing
  `displayNamesFor` + `parentTitleOf` + `toReplyViews` helpers
  from #66 / #67 — same view-construction rhythm so the wire
  shape matches the per-block list. Batch-fetches replies (one
  query for all rows) + display names (one query) + parent
  titles (one per kind). Round-trips stay constant regardless
  of thread count.
- `apps/api/src/main/java/com/chessedu/platform/classes/MeBlockQuestionController.java`
  — added `GET /questions` route returning the rollup. Sits
  next to the existing `/questions/{id}` per-thread endpoint.
- `apps/web/src/app/[locale]/student/qa-actions.ts` — added
  `fetchAllMyThreadsAction()` wrapping the new endpoint. Returns
  the same `StudentQaListResult` union as the per-block list
  action so callers can share error-mapping helpers.
- `apps/web/src/app/[locale]/student/questions/page.tsx` — new
  server component. Fetches `/api/v1/me/questions` in parallel
  with the auth `me` check, gates STUDENT-only, redirects on
  401/403. Renders a `<ul>` of `.student-questions-row` items;
  each row's body, asked-at, reply count, last-reply meta, and
  status pill come from the rollup payload. `formatRelative()`
  helper uses `Intl.RelativeTimeFormat` for short labels with a
  Tashkent-localised short-date fallback for ≥7-day-old rows.
- `apps/web/src/app/[locale]/student/sidebar.tsx` — extended
  `Section` union with `"questions"`, added a `sectionFromPath`
  arm that matches `/student/questions`, and inserted a live
  `<Link href="/student/questions">` between Lessons and
  Puzzles in the LEARN group. The placeholder `student-sidebar__item--disabled`
  span pattern stays for Puzzles / Classmates / Tournaments.
- `apps/web/messages/{en,ru,uz}.json` — added 9 new keys per
  locale (893 → 902 total each, parity verified): one
  `nav.questions` for the sidebar entry + a `questionsList.*`
  subtree (8 keys: `title`, `subAll` plural, `subOpen` plural,
  `empty`, `loadError`, `openBadge`, `askedAt`, `lastReply`).
  Russian uses full `one/few/many/other` plurals; Uzbek uses
  `one/other`.
- `apps/web/src/app/globals.css` — appended ~120 lines under a
  new "Student cross-class questions rollup (Lane B / B4)"
  header. Key primitives: `.student-questions-list` (column flex,
  10px gap); `.student-questions-row` (paper-elevated card with
  hover-tinted background + accent-tinted border); `--resolved`
  modifier (subtle opacity drop); `.student-questions-row__link`
  (full-row clickable wrapper with focus-visible accent ring);
  `.student-questions-row__head` (flex-wrap row with parent
  label + status pill); `.student-questions-row__pill--open` /
  `--resolved` variants; `.student-questions-row__body` (15px
  body copy); `.student-questions-row__meta` (flex-wrap meta
  strip with tabular-nums); responsive override at ≤560px
  drops the `margin-left: auto` on the pill so it sits next to
  the parent label rather than far-right; dark-mode override on
  the row background. Brace balance verified: 1084 / 1084.

Build verify: backend code reviews clean against existing
patterns (matches the shape of `inbox` from #66 line-for-line
with the auth gate swapped from `authoriseClass` →
`requireStudent`). Frontend typecheck + i18n parity + CSS brace
balance all clean in the sandbox. Run
`./gradlew :api:compileJava` locally to confirm the Java
compile.

Deliberately scoped out:

- **Count badge on the sidebar** — see above.
- **Activity-based ordering** — see above.
- **Status filter** — see above.
- **Inline thread expansion on the rollup** — clicking a row
  navigates to the parent. Inline expansion would duplicate the
  per-block QA panel's UX with a different mount point;
  unnecessary in v1 since the parent page is one click away.
- **Search inside the rollup** — list is small (typically
  &lt;20 threads); search is premature.
- **Cross-class admin equivalent** — same shape would work for
  teachers wanting to see all questions across all their
  classes. File when classroom-comms volume warrants it; for
  now the per-class QA inbox tab covers the use case.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100).
2. Sign in as `student@demo.local` / `student12345`. Open a
   homework or lesson, click **Ask teacher** under any block,
   send a question. Repeat for a different homework / lesson
   in a different class so the rollup has cross-class rows.
3. Click **Questions** in the left sidebar (under LEARN, between
   Lessons and Puzzles). URL becomes `/en/student/questions`.
4. Happy path — list renders:
    1. Page title reads **Your questions**. Subtitle:
       **2 questions · 2 open**.
    2. Two rows stacked vertically, newest first. Each row
       shows: parent kind label (**HOMEWORK** / **LESSON**) +
       title + **Block N** label, status pill on the right
       (**Open** in accent green), question body snippet,
       meta strip with **Asked &lt;when&gt;** + **No replies yet**.
    3. Hover a row — background tints, border picks up an
       accent edge. Click it — page navigates to the parent's
       detail page.
5. Happy path — admin replies:
    1. Switch to admin (`admin@demo.local`), navigate to the
       class's QA tab, type a reply, send.
    2. Switch back to the student. Refresh
       `/en/student/questions`. The first row's meta strip now
       shows **1 reply** + **Last reply &lt;when&gt; by &lt;admin name&gt;**.
6. Happy path — resolve:
    1. Click into a row → parent detail page → **Ask teacher**
       under the block → click **I've got it** to self-resolve.
    2. Navigate back to **Questions**. The resolved row picks up
       a faded treatment with a **Resolved** pill instead of
       **Open**. The subtitle updates: **2 questions · 1 open**.
7. Edge cases worth eyeballing:
    - **Empty state**: a fresh student who hasn't asked
      anything sees the dashed-border empty card with the
      prompt to use the per-block "Ask teacher" pill.
    - **Network failure**: kill the API (`ctrl-C`), refresh the
      page. The error banner appears: "Couldn't load your
      questions. Refresh to try again." Restart and refresh.
    - **Archived parent**: ask a question on a homework, have
      admin archive the homework. The rollup still shows the
      thread (historical record), with the parent title still
      filled in. Click the row — navigates to the homework
      detail page, which 404s server-side per the existing
      archived-parent rule. Acceptable trade-off: the rollup's
      job is recall, not active workflow.
    - **Responsive ≤560px**: open DevTools → device toolbar
      → iPhone SE. The status pill drops its
      `margin-left: auto` and sits next to the parent label
      so the row stays readable.
    - **Dark mode**: toggle theme. Row backgrounds darken via
      the dark-mode override; the open/resolved pills retain
      contrast.
    - **`/ru/student/questions`** / **`/uz/student/questions`**
      — all 9 new keys translate. Russian renders the
      subtitles with full `one/few/many/other` plural forms
      ("1 вопрос" / "2 вопроса" / "5 вопросов"); Uzbek uses
      `one/other` ("1 ta savol" / "5 ta savol"). Sidebar
      entry reads **Вопросы** / **Savollar**.
    - **STUDENT role gate**: log in as admin and paste
      `/en/student/questions` — bounces to `/en/admin`.

### #72 — Teacher view of student attempt history (B3)

Lane-B slice. The backend's been exposing
`GET /api/v1/admin/classes/{cid}/homework/{hwId}/students/{sid}/blocks/{idx}/attempts`
since #29-#35 — every attempt on the (homework, student, block)
triple, oldest first, with `replacedById` chaining the supersedes
relationship — but the only consumer was the integration test. The
admin Submissions sheet showed only the **live** attempt per
student per block. So a teacher who wanted to scroll back through a
student's prior submissions had no UI surface for it.

This slice mounts a "View past attempts (N)" disclosure inside each
`AttemptReviewCard`. Click → lazy-fetches the full chain → renders
each attempt as a compact row with timestamp, status pill, ordered
UCI move chips, optional teacher note, and a "Superseded" /
"Current" tag distinguishing the live row from replaced ones.

What the disclosure shows per historical attempt:

- **Position counter** (`#1`, `#2`, …) in the corner, oldest first.
- **Submitted timestamp** (Tashkent wall-clock via the existing
  `formatInstant`).
- **Status pill** with the same display-status mapping the live
  attempt uses (`SUBMITTED` / `REVIEWED_OK` / `REVIEWED_NEEDS_WORK`
  / `SOLUTION_REVEALED`). The derived `SOLUTION_REVEALED` covers
  the "needs work + student revealed answer" case the same way the
  live card does it.
- **Live / Superseded tag**: live row picks up an accent-soft
  "Current" pill + a tinted background; replaced rows get a
  muted-tone "Superseded" pill.
- **Move list** as JetBrains-mono UCI chips. Compact — no
  per-attempt board replay (would balloon the UI on chains of 5+
  attempts; the live attempt's board is right above and the moves
  are easy to read).
- **Teacher note** if present — same scholarly-quote chrome as
  elsewhere (3px accent left-border on a paper-sunken backdrop).
- **Reviewed-at line** in italic muted ink when the row was
  reviewed.

The disclosure is collapsed by default — wouldn't want to fire a
separate fetch per student per block on first paint of the
Submissions sheet (a class with 20 students × 3 blocks = 60
fetches). Lazy on first expand; cache reused on subsequent
toggles within the same render cycle. When a fresh review lands
(the live row's `attempt.id` changes because of a student
resubmit), a `useEffect` invalidates the cached history so the
next expand re-fetches with the new chain.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/homework-actions.ts`
  — added `fetchAttemptHistoryAction(classId, homeworkId,
  studentUserId, blockIndex)` wrapping the existing endpoint.
  Returns `{ok: true, history: BlockAttemptView[]}` or the
  standard error union; same `mapErrorStatus` and shape as the
  other actions in the module.
- `apps/web/src/app/[locale]/admin/classes/[id]/block-review-panel.tsx`
  — extended `BlockReviewLabels` with 9 new history-specific
  fields (`historyShow`, `historyHide`, `historyEmpty`,
  `historyLoading`, `historyAttemptMeta`, `historyReplacedBadge`,
  `historyLiveBadge`, `historyMovesLabel`, `historyNoMoves`).
  Added 4 new `useState` slots in `AttemptReviewCard` for the
  disclosure (`historyOpen`, `history`, `historyLoading`,
  `historyError`). Lazy-fetch wrapped in the same
  `useEffect` + `cancelled` flag pattern as the other QA panels —
  with the same CAREFUL docblock that `historyLoading` must NOT
  be in the dep array (cleanup-mid-fetch race). Mounted the
  disclosure section between the existing `error` block and the
  closing `</article>` so the chrome reads as a "drill-down"
  appended to the live attempt UI.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — added 9 `t("sheet.review.history…")` lookups inside
  `blockReviewLabels`, threading the new copy through to the
  panel.
- `apps/web/messages/{en,ru,uz}.json` — 9 new keys per locale
  under `admin.classes.homework.sheet.review` (884 → 893
  total each, parity verified). `historyShow` is plural-aware:
  English `=0` falls through to "View attempt history" (no
  count), `one` / `other` render "View past attempts (N)".
  Russian uses the full `one/few/many/other` plural set; Uzbek
  uses `one/other`.
- `apps/web/src/app/globals.css` — appended ~190 lines under a
  new "Attempt history disclosure on the AttemptReviewCard
  (Lane B / B3)" header. Key primitives:
  `.hw-review-card__history` (column flex with dashed top
  border so the disclosure reads as a follow-up to the review
  controls); `.hw-review-card__history-toggle` (matching the
  other neutral disclosure pills used elsewhere on the page);
  `.hw-review-card__history-list` with `counter-reset:
  hw-history` so each row picks up a `#1` / `#2` corner
  counter via `::before`; `.hw-review-card__history-row`
  (paper-elevated card per attempt) + `--live` modifier
  (accent-tinted background on the current row);
  `.hw-review-card__history-tag` + `--live` /
  `--replaced` variants; `.hw-review-card__history-uci-chip`
  (JetBrains-mono pill list); `.hw-review-card__history-note`
  (scholarly-quote 3px accent-left-border treatment matching
  the live attempt's teacher-note display).
  Brace balance verified: 1067 / 1067.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0). i18n parity: en=893, ru=893, uz=893.

Deliberately scoped out:

- **Per-attempt board replay** — historical rows render move
  chips only; no `BoardCardView` per row. The teacher can
  visualise from the moves; if they want a board, the live
  attempt is right above (and the previous attempt's moves
  starting position is identical). A future enhancement could
  add a "step through this attempt's moves" expander on each
  row, but that's a heavier UX (per-row stepper state, focus
  management for accessibility).
- **Filter / sort controls** — no "show only NEEDS_WORK" or
  "newest first" toggle. The expected chain length per block
  is small (typically 1-3 attempts before resolution), so
  filtering would be premature.
- **Diff between consecutive attempts** — could highlight what
  moves changed between attempt #2 and #3. Out of scope for v1.
- **Audit log of teacher reviews** — the reviewedAt + reviewedBy
  fields exist on each row; we surface `reviewedAt` per row but
  not `reviewedBy`. The current teacher reviewing the row
  doesn't really care which colleague reviewed prior attempts
  (they have the note), and adding a "Reviewed by" line on
  every historical entry would clutter the small rows. File
  if a customer asks.
- **Bulk operations** — no "mark all of this student's history
  as OK". Each historical row is read-only; only the live row
  has Mark OK / Mark Needs work buttons. By design — past
  attempts are immutable.

Build verify: backend hasn't changed; the existing endpoint just
gets a new caller. Frontend typecheck + i18n parity + CSS brace
balance all clean.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the frontend.
2. Sign in as `student@demo.local` / `student12345`. Open a
   homework with at least one BOARD block. Submit a wrong
   sequence (drag e.g. `a2a3` for white, click Submit).
3. Sign in as `admin@demo.local` / `admin12345` in another
   browser. Open the class → Homework row → **Submissions**.
   Find the student's row, click **Mark needs work**. Add a
   note: "Try the e-pawn first."
4. Switch back to student. Refresh — the block now shows
   **Needs work** with the teacher's note. Submit a different
   sequence (drag `e2e4` then `e7e5`).
5. Back to admin. Refresh the Submissions sheet. The student's
   live attempt now shows the new moves and is back to
   **Awaiting review**.
6. Happy path — view history:
    1. At the bottom of the student's review card, click
       **View past attempts (1)**. (The count is 1 because the
       chain has 1 superseded attempt; the live row is shown
       above and isn't counted in the disclosure label until
       expanded.)
    2. The disclosure expands. A brief "Loading history…"
       flash, then a list of historical attempts oldest-first:
       - Row #1: **Submitted &lt;when&gt;**, **Needs work** pill,
         **Superseded** tag, the moves `a2a3`, the teacher
         note "Try the e-pawn first.", and a reviewed-at
         italic line.
       - Row #2: **Submitted &lt;when&gt;** (newer), **Awaiting
         review** pill, **Current** tag with an accent-tinted
         background, the moves `e2e4`, `e7e5`. No teacher
         note (not yet reviewed).
    3. The toggle button now reads **Hide attempt history**.
       Click — the disclosure collapses.
7. Edge cases worth eyeballing:
    - **First-time view**: a student who's only ever submitted
      once shows **No prior attempts on this block.** The list
      header is suppressed (history.length is 1, falls into
      the "<= 1" empty-state branch).
    - **Long chain**: have the student submit and the teacher
      mark Needs work 5+ times. The disclosure's count badge
      shows the chain length (rows shown oldest first, current
      live row at the bottom with accent tint).
    - **Cache invalidation on resubmit**: expand history, then
      switch to the student tab and submit a new attempt.
      Switch back to admin, click Mark needs work on the
      now-newer live row. The history disclosure auto-reset
      (because `attempt.id` changed). Re-expand → fresh fetch
      shows the chain extended.
    - **Network failure**: kill the API (`ctrl-C`), click
      **View past attempts**. Error banner: "Couldn't reach
      the server. Try again." Restart the API and click again
      — works.
    - **Solution-revealed mid-chain**: the student reveals the
      authored solution after a Needs work review (per Task
      #29-#35's reveal endpoint). The current live row's
      display state becomes **Solution revealed**; the
      historical row that prompted the reveal stays at
      **Needs work**. The pill mapping is per-row so the
      derived state surfaces only on rows that have a
      `solutionRevealedAt` stamp.
    - **Read-only mode**: an unassigned teacher hits the
      page → the Submissions sheet itself is hidden by the
      existing `canEdit` gate. The history disclosure is
      gated alongside it; if the gate ever flips, the
      disclosure inherits read-only naturally — historical
      rows have no interactive elements.
    - **Dark mode**: toggle theme. Live-row tint deepens
      (accent-fill 8% → 20%), historical-row paper darkens.
      The `#N` corner counter stays visible against both
      backgrounds.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — all 9 new keys translate. Russian's plural-aware
      historyShow renders "Показать прошлые попытки (1)" /
      "Показать прошлые попытки (5)" (same form across
      `one/few/many/other` since the noun "попыток" stays
      genitive-plural either way; the count number changes).
      Uzbek "Avvalgi urinishlarni ko‘rsatish (1)" /
      "Avvalgi urinishlarni ko‘rsatish (5)".
    - **Responsive ≤560px**: the disclosure inherits the
      existing `.hw-review-card` ≤560px padding tightening;
      historical rows stack their head-row chips vertically
      via the natural `flex-wrap`. Counter stays at the
      corner.

### #71 — Student lesson viewer polish: auto-NOT_STARTED + keyboard shortcuts

Two small Lane-B slices shipped together since they touch the same
viewer component and don't justify separate entries.

#### B1 — Auto-mark NOT_STARTED on first lesson open

The `LessonCompletion` schema has carried a tri-state grain
(`READ` / explicit `NOT_STARTED` / null = "no row yet") since #49,
and the backend's `PUT /api/v1/me/lessons/{id}/completion` has
accepted explicit `NOT_STARTED` writes since #52 (a deliberate
contract divergence from the homework twin — lessons are revisitable
study material so the "opened but didn't finish" trail is a
meaningful signal). The student lesson viewer just never wrote that
trail. Now on first open of `/student/lessons/{id}`, if
`detail.status` is null the panel fires a fire-and-forget
`finishLessonAction` with `status=NOT_STARTED`. The PUT triggers
`revalidatePath` which flips the chip from "Unmarked" to "In
progress" on next paint, plus updates the admin completion sheet's
read-tally upstream.

The auto-mark gate (`status !== null && status !== undefined`) is
load-bearing: it only fires on the truly null case, NOT on a row
that's been explicitly READ-then-cleared. Once the row exists with
NOT_STARTED, the effect re-runs after revalidate but the gate skips.
A subsequent READ via the explicit chip also re-runs the effect but
skips. No infinite-loop risk.

CAREFUL: deliberately did NOT include `detail.id` / `locale` as
"trigger if these change" in the dep array — they don't change
across renders of the same mounted detail page. They're in the dep
array only to satisfy exhaustive-deps lint without suppressing it.

#### B2 — Keyboard shortcuts on lesson walkthrough stepper

`LessonBoardWalkthroughView` (the BOARD_WALKTHROUGH viewer) now
accepts arrow-key navigation. Wiring:

- `←` / `ArrowLeft` → previous ply (clamps at 0)
- `→` / `ArrowRight` → next ply (clamps at lastPly)
- `Home` → jump to ply 0 (starting position)
- `End` → jump to lastPly (final position)

The article element wrapping each walkthrough block is now
`tabIndex={0}` so it's reachable via Tab. The `onKeyDown` handler
gates on the absence of modifier keys (Alt / Ctrl / Meta / Shift)
and calls `e.preventDefault()` for arrow keys to suppress the
default page-scroll behaviour. Multiple walkthroughs on one
lesson are independent — focus is per-card, so pressing
`ArrowRight` only advances the card that has focus.

A `:focus-visible` outline (2px accent ring) appears on keyboard
focus only — click users don't see a ring (avoids the click-then-
ring noise on the card surface).

`role="application"` deliberately NOT added — screen readers will
announce it as an `<article>` with the title from `aria-label`,
which reads more naturally than "application" for a study-material
block. Arrow-key behaviour still works because the keydown handler
runs before any default text-navigation logic the screen reader
might inject.

Files:

- `apps/web/src/app/[locale]/student/lessons/[id]/detail-panels.tsx`
  — added `useEffect` block in `StudentLessonDetailPanels` that
  fires the auto-NOT_STARTED PUT on first open + a new
  `KeyboardEvent` import + `onCardKeyDown` handler on
  `LessonBoardWalkthroughView`'s outer `<article>` + a
  `tabIndex={0}` and `aria-label={title}` so screen readers
  announce the card's title on focus.
- `apps/web/src/app/globals.css` — added `:focus` (outline:none)
  + `:focus-visible` (2px accent ring, 2px offset) rules on
  `.lesson-viewer__block--walkthrough`. Brace balance
  1040 → 1042.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0). No i18n changes (B1 reuses the existing status chip
copy; B2 has no user-facing copy).

Deliberately scoped out:

- **Auto-mark on `/student/homework/{id}`** — homework's
  completion state is binary (DONE / SKIPPED / null) and the
  backend explicitly rejects an explicit NOT_STARTED write
  (contrast with lessons). Auto-marking would require a
  dedicated "homework opened" timestamp column + new endpoint.
  File only if real-world feedback shows teachers want it.
- **Visible kbd hints in the UI** — could add a small
  "← / → to navigate" footer note under the walkthrough
  stepper. Decided against in v1 to keep the chrome quiet;
  power users discover the shortcuts; non-power users have
  the buttons.
- **Vim-style `j` / `k` / `g` / `G`** — not in scope; arrow
  keys cover the discovery path.
- **Esc to blur the card** — browser default Tab still walks
  focus away. Esc would be redundant.
- **MCQ keyboard shortcuts (digits 1–4 to pick options)** —
  probably nice but each MCQ is a `<form>` with native
  keyboard support already (Tab to options, Space / Enter to
  select). File if users ask.
- **Auto-scroll the walkthrough card into view on focus** —
  default browser focus behaviour handles this for explicit
  Tab-key focus; click focus is already at the viewport.

How to check as a user:

1. Sign in as `student@demo.local` / `student12345`. Open a
   lesson with at least one BOARD_WALKTHROUGH block authored
   with 5+ moves.
2. **B1 — auto-NOT_STARTED**:
    1. Before opening, the lesson list (`/en/student/lessons`)
       shows the lesson with the **New** chip (status null).
    2. Click the row to open the detail page. The status pill
       at the top initially reads **Unmarked** but within
       ~150ms (revalidate round-trip) flips to **In progress**.
       The chip on the lesson list (if you navigate back) now
       also reads **In progress**.
    3. As `admin@demo.local`, open the class's **Lessons**
       panel and click the row's completion sheet — the
       student now appears with the **Not started** chip plus
       a "marked at" timestamp.
    4. Mark the lesson as **Read** via the chip on the detail
       page. Refresh — chip stays **Read** (no auto-revert).
    5. Click **Clear status** — chip flips back to **Unmarked**
       briefly, then auto-NOT_STARTED kicks in again. The
       admin completion sheet now shows **Not started** again.
3. **B2 — keyboard shortcuts**:
    1. On the lesson detail page, scroll to the
       BOARD_WALKTHROUGH block. Click anywhere on the card
       (or press Tab until the card has focus — a green
       outline appears on Tab focus only).
    2. Press **→**. The board steps forward one ply; the
       move-list rail's active chip moves to the next move.
    3. Press **→** several times. The board advances; the
       button's disabled state matches (when at lastPly, the
       next-button on the visible stepper is disabled).
    4. Press **End**. The board jumps to the final position.
    5. Press **Home**. Back to the starting position.
    6. Press **←** several times — board steps backward,
       clamps at ply 0.
    7. Modifier-key combos (Cmd-→, Ctrl-Home, etc.) are
       ignored — the keydown handler bails on any modifier
       so OS-level shortcuts (Cmd-Right = end of line) still
       work in any input on the page.
4. Edge cases worth eyeballing:
    - **Multi-walkthrough**: open a lesson with 2+
      BOARD_WALKTHROUGH blocks. Tab into each card; arrow
      keys only advance the focused card (the other stays
      put).
    - **PROSE / MCQ blocks**: arrow keys do nothing on those
      cards (they don't have the keydown handler).
    - **Refresh during auto-mark**: hard-refresh the page
      while the auto-mark request is in flight. The PUT
      completes server-side regardless; the next paint shows
      the row already at NOT_STARTED.
    - **Backend offline at first open**: the auto-mark PUT
      fails silently (finishLessonAction swallows errors).
      The chip stays at **Unmarked**. When backend recovers,
      next nav refreshes the page and the auto-mark fires
      again — eventually the row materialises.
    - **`/ru/student/...`** / **`/uz/student/...`**: the
      auto-mark behaviour is locale-agnostic (no UI copy
      changes); the chip wording follows the existing
      `lessonDetail.statusPill.NOT_STARTED` translation
      ("В процессе" / "Boshlangan").
    - **Screen reader**: tabbing to the walkthrough card
      announces "{lesson title}, application" or
      "{lesson title}, article" depending on the SR. After
      that, arrow-key feedback comes from the ARIA-live regions
      already present in the side-to-move pill (which updates
      on every ply change).

### #70 — Admin per-block QA overlay on Submissions sheet

Closes the classroom-comms umbrella's last frontend slice. Mounts a
per-block QA disclosure inside the homework **Submissions** sheet
(`BlockReviewSection`), so a teacher reviewing attempts on a block
can see + answer + resolve student questions inline without
bouncing to the QA tab. Reuses the
`/api/v1/admin/classes/{cid}/homework/{hwId}/blocks/{idx}/questions`
overlay endpoint that's been sitting on the backend since #66.

What the overlay does:

- **Per-block disclosure** below each block's attempts list. Toggle
  reads "Show student questions" (collapsed) / "Hide student
  questions" (expanded). After first expand the toggle picks up a
  count badge: total threads on this block, plus a `· N open`
  suffix when the open subset differs from the total.
- **Lazy-fetches** on first expand (same effect-deps trap fix as
  #69 / the qa-inbox panel — `loading` is in the gate but not in
  the deps array, with a CAREFUL docblock spelling out why).
- **Renders threads** with the same `.qa-inbox__*` chrome the
  class-level inbox uses (head message, role-tinted reply cards,
  reply textarea + Send + Mark resolved actions, italicised
  resolved-meta line).
- **Reuses** the existing admin `replyToThreadAction` and
  `resolveThreadAction`. No new write actions — both already
  `revalidatePath` the class detail page so the QA tab badge +
  inbox preview list stay accurate after the overlay edits a
  thread.
- **Read-only mode** when the parent's `canEdit` is false (an
  unassigned teacher viewing the page) — the toggle still surfaces
  threads but the reply form + resolve button are hidden. The
  backend re-checks on every write so a crafted POST still 403s.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/qa-actions.ts` —
  added `fetchBlockOverlayAction(classId, parentKind, parentId,
  blockIndex)` that wraps the per-block overlay endpoint. Returns
  the same `BlockQuestionThreadView[]` shape the existing types
  carry; reuses `mapStatus` / `getAccessToken`. The `parentKind`
  arg lets the action work for both homework + lesson overlays
  (lesson-side mounts can land in a follow-up).
- `apps/web/src/app/[locale]/admin/classes/[id]/block-qa-overlay.tsx`
  — new `"use client"` module (~370 lines). `BlockQaOverlay`
  (outer disclosure + lazy fetch + threads list) +
  `BlockQaThreadCard` (head + replies + reply form + resolve
  button per thread). Mirrors the qa-inbox row card structure
  but always renders expanded (the disclosure is at the panel
  level, not per thread), and shows the asker's name + role pill
  in the head row of every thread (the inbox shows it in the
  collapsed preview row only). Same `formatRelative` helper +
  Tashkent fallback. Optimistic local state (`replaceThread`)
  splices fresh threads from reply / resolve into the in-memory
  list so the UI repaints without refetching.
- `apps/web/src/app/[locale]/admin/classes/[id]/block-review-panel.tsx`
  — extended `BlockReviewSection` with an optional `canEdit?:
  boolean` prop (default `true` for backwards-compatibility with
  any caller not yet updated). Mounted `<BlockQaOverlay>` at the
  bottom of every per-block `<section>` in the review map, after
  the attempts list. The mount uses `parentKind="HOMEWORK"`
  hard-coded — the only consumer of `BlockReviewSection` today
  is the homework Submissions sheet; lessons would need their
  own block-review surface (which doesn't exist) before they
  could pipe `parentKind="LESSON"` through.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — passed the existing `canEdit` flag through to
  `<BlockReviewSection>` so the new overlay's read-only mode
  follows the same gate the rest of the page uses.
- `apps/web/messages/{en,ru,uz}.json` — 4 new keys per locale
  under `admin.classes.qa.*` (884 total each, up from 880,
  parity verified): `overlayShow`, `overlayHide`, `overlayEmpty`,
  and the plural-aware `overlayOpenSuffix` ("{count} open" /
  full Russian `one/few/many/other` forms / Uzbek `one/other`).
- `apps/web/src/app/globals.css` — appended ~80 lines under a
  new "Per-block QA overlay on the homework Submissions sheet
  (Task #70)" header. Key primitives: `.hw-review-block__qa`
  (column flex with dashed top border so the overlay reads as a
  follow-up to the attempts list); `.hw-review-block__qa-toggle`
  (matches the student `student-qa__toggle` accent-pill chrome
  for visual consistency); `.hw-review-block__qa-count` (small
  rounded count pill on the toggle); `.hw-review-block__qa-panel`
  (column flex container); `.hw-review-block__qa-empty`
  (dashed-border italic-muted card for the "no questions yet"
  state); `.hw-review-block__qa-list` (list reset). The thread
  cards inside reuse `.qa-inbox__*` chrome — no duplicate styles.
  Brace balance verified: 1040 / 1040.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).
i18n parity: en=884, ru=884, uz=884.

Deliberately scoped out:

- **Lesson-side overlay** — admins don't have a per-block lesson
  review surface today (the admin lesson panel renders a list +
  edit form + read-only completion sheet, no per-block view).
  When that lands, mounting `BlockQaOverlay` with
  `parentKind="LESSON"` is a one-line addition; the action
  + component already accept the kind.
- **Lesson editor mount** — could surface "questions on this
  block" inside the LessonBlocksEditor while authoring, but
  that's a different UX (the author isn't reviewing student
  work, they're editing content). Ship if real-world feedback
  asks for it.
- **Inline new-thread form for teachers** — overlay is read +
  reply only. A teacher can't open a new thread on a block
  themselves (consistent with the v1 backend stance: "only
  students open threads"). If a teacher wants to flag something
  on a block, they post a teacher-note on the attempt
  (Task #28's mechanism) or use the homework instructions
  field.
- **Notification when a new thread lands on a block being
  reviewed** — same notification umbrella that's been deferred
  across #66 / #67 / #68 / #69. v1 relies on the teacher
  refreshing the page or revisiting the QA tab.
- **Cross-class roll-up + sidebar badge** — same as #68; the
  overlay surfaces in-context per block, the QA tab covers
  per-class. A future `/admin/inbox` would aggregate across
  classes.
- **Search inside the overlay** — overlay is per-block, so the
  expected thread count is small (a handful at most). A search
  box would be premature.

Build verify: same as the previous frontend slices — backend
hasn't changed, only the existing per-block overlay endpoint
gets a new caller. Frontend typecheck + i18n parity + CSS
brace balance all clean in the sandbox.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as `student@demo.local` / `student12345`. Open a
   homework with at least one BOARD block, attempt the block
   (drag a move, click Submit). Then under the same block,
   click **Ask teacher**, type a question, send. Repeat for a
   second block if the homework has two.
3. Log out, sign in as `admin@demo.local` / `admin12345`. Open
   the same class, scroll to **Homework**, click **Submissions**
   on the row.
4. Happy path — overlay rendering:
    1. The Submissions sheet expands. Below each block's
       attempts list, a new disclosure pill **Show student
       questions** appears below a dashed top-border separator.
    2. Click the pill. URL doesn't change (lazy-fetch via
       server action, no router push). A brief loading dash
       flashes; then the panel expands to show the student's
       thread:
       - Asker name + **Student** role pill + relative-time
         label on the head row.
       - Question body in a paper-elevated card.
       - A reply form below with textarea + **Mark resolved**
         (ghost) + **Send reply** (primary) buttons.
    3. The pill flips to **Hide student questions** and picks
       up a count badge "1" on the right.
5. Happy path — reply:
    1. Type a reply ("Try playing 1.e4 e5 2.Nf3 first.") and
       click **Send reply**. Button reads **Sending…**, then
       the thread updates inline with the new teacher reply
       (accent-tinted card with **Teacher** role pill).
    2. The QA tab badge on the class detail page updates
       silently in the background via `router.refresh()`
       (the action's `revalidatePath` triggered it).
6. Happy path — resolve:
    1. Click **Mark resolved**. Button reads **Resolving…**,
       then the thread flips to a faded "resolved" treatment
       with a **Resolved** badge on the head row + an
       italicised "Resolved {when} by {by}" closing line. The
       reply form disappears.
    2. The toggle's count badge stays at 1 (resolved counts
       toward the total) but the `· N open` suffix
       disappears.
    3. Open the QA tab on the class detail (different tab) —
       the inbox no longer lists this thread (it's resolved).
7. Edge cases worth eyeballing:
    - **Empty block**: scroll to a block with no questions
      seeded. Click **Show student questions** → panel reads
      "No questions on this block yet." in italic muted ink.
    - **Multiple students**: enrol a second student in the
      class, have both open threads on the same block. The
      overlay lists both threads, oldest-first, each with
      their own asker name + role pill.
    - **Open + resolved mix**: resolve one of two threads.
      Overlay shows "2 · 1 open" on the toggle; the resolved
      thread renders below the open one with the faded
      treatment.
    - **Network failure**: kill the API (`ctrl-C`), click the
      toggle. Error banner appears with the network-error copy.
      Restart the API + click toggle again — works.
    - **Stale view (409)**: open the QA tab in another browser
      tab and resolve the thread there. Switch back to the
      Submissions tab and click **Send reply** → 409 →
      "Thread is resolved or your view is stale. Refresh."
      copy under the form.
    - **Read-only**: sign in as a teacher who isn't on the
      class roster. The Submissions sheet is hidden entirely
      (the existing canEdit gate). If the gate is ever
      relaxed, the overlay supports `canEdit=false` — toggle
      + thread list visible, reply form + resolve button
      hidden.
    - **Responsive ≤560px**: open DevTools → device toolbar
      → iPhone SE. The toggle pill stays at the top; the
      thread cards stack with the existing qa-inbox responsive
      override (action row collapses, buttons stretch).
    - **Dark mode**: toggle theme. The overlay's accent-pill
      toggle deepens, the count badge picks up the darker
      paper-sunken tone, thread cards inherit the qa-inbox
      dark-mode overrides (teacher-reply tint lifts the
      accent percentage).
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — the four new keys translate. Russian's `overlayOpenSuffix`
      uses the full `one/few/many/other` plural forms ("1
      открыт" / "2 открыто" / "5 открыто"). Uzbek uses
      `one/other` ("1 ta yopilmagan" / "5 ta yopilmagan").

### #69 — Student per-block "Ask teacher" affordance

Closes the second frontend slice of the classroom-comms umbrella.
Mounts a per-block QA disclosure under every block on the student's
homework + lesson detail pages. Collapsed by default ("Ask teacher"
pill); expanding lazy-fetches the student's existing threads on the
block via `GET /api/v1/me/{kind}/{id}/blocks/{idx}/questions` and
renders:

- Each existing thread: head + replies + reply form (when not
  resolved) + "I've got it" self-resolve button. Replies are
  role-coloured the same way as the admin inbox (paper for student,
  accent-tinted for teacher).
- A new-question form below the threads, always visible — multi-
  thread per block is supported by the backend, and the v1 UX
  doesn't gate it (no "you already have an open thread" lock).

Server actions call the seven student endpoints from #67:
`fetchMyBlockThreadsAction` (lazy detail load), `openMyThreadAction`
(`useActionState`-shaped for the new-question form),
`replyMyThreadAction` (same shape for the reply form), and
`resolveMyThreadAction` (button-click). Both write actions
`revalidatePath` the parent detail page so the surrounding context
stays consistent on navigation.

The same `StudentBlockQa` component mounts on both surfaces — the
homework `BlockAttemptPanel` map gets a wrapper div that renders the
panel below the attempt UI, and the lesson viewer's kind-switch is
factored to render `body` + `<StudentBlockQa>` as a `<Fragment>` per
resource so PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO + the unknown-
kind fallback all get the affordance. The component takes
`parentKind`, `parentId`, `blockIndex`, and `locale` as props — no
runtime detection of which kind it's mounted under.

Files:

- `apps/web/src/app/[locale]/student/qa-actions.ts` — new server
  actions module (~230 lines). Mirrors the admin
  `qa-actions.ts` shape (same error union, same `mapStatus`)
  with parent-kind aware URL building (`blockUrl(kind, id, idx)`
  switches between `/api/v1/me/homework/...` and
  `/api/v1/me/lessons/...`). Four actions: list (regular async
  function), open (useActionState shape, FormData payload),
  reply (same), resolve (button shape, returns result union).
  Revalidate path resolves to `/student/homework/{id}` or
  `/student/lessons/{id}` based on parentKind.
- `apps/web/src/app/[locale]/student/qa-block-panel.tsx` — new
  client component (~340 lines). `StudentBlockQa` (outer
  toggle + lazy-fetch + new-question form) + `StudentQaThreadCard`
  (head + replies + reply form + resolve button). Lazy detail
  fetch wrapped in `useEffect` (same React 19 strict-mode fix
  pattern as the EditForm components). `formatRelative(iso, locale)`
  helper for short relative-time labels via
  `Intl.RelativeTimeFormat` with a Tashkent-localised short-date
  fallback for ≥7-day-old rows. Optimistic local state appends
  new threads + replaces in-place on reply / resolve.
- `apps/web/src/app/[locale]/student/homework/[id]/detail-panels.tsx`
  — extended the `pairs.map` block: each board now renders inside
  a wrapper `<div className="student-hw-detail__block">` that
  contains the existing `BlockAttemptPanel` plus a new
  `<StudentBlockQa parentKind="HOMEWORK" parentId={detail.id}
  blockIndex={blockIndex} locale={locale} />` below it.
- `apps/web/src/app/[locale]/student/lessons/[id]/detail-panels.tsx`
  — refactored the kind-switch inside `sortedResources.map` to
  bind the rendered block to a local `body` variable, then return
  a `<Fragment key={r.id}>` wrapping `body` + the new QA panel.
  Same parentKind="LESSON" + parentId=detail.id pattern.
- `apps/web/messages/{en,ru,uz}.json` — new `student.qa.*` subtree
  (23 keys per locale, 857 → 880 total, parity verified). Covers:
  toggle copy (`ask` / `hide`), form labels (`firstLabel` /
  `anotherLabel` / `placeholder`), submit + reply + resolve copy
  with pending variants, role labels (`roleStudent` / `roleTeacher`),
  resolved badge + post-resolve notice, and seven error-code
  strings matching the action union (`errorInvalid` /
  `errorUnauthorised` / `errorForbidden` / `errorNotfound` /
  `errorConflict` / `errorNetwork` / `errorUnknown`).
- `apps/web/src/app/globals.css` — appended ~250 lines under a new
  "Student per-block 'Ask teacher' affordance (Task #69)" header.
  Key primitives: `.student-qa` (column flex with dashed top
  border so the QA section reads as a follow-up to the block);
  `.student-qa__toggle` (dashed accent-pill button that flips
  between "Ask teacher" and "Hide questions" labels);
  `.student-qa__panel` (paper-sunken card with the threads list
  + new-question form); `.student-qa__thread` + `--resolved`
  (paper-elevated card per thread, slightly faded when resolved);
  `.student-qa__msg` + `--reply` + `--teacher` (role-tinted
  message cards inside threads); `.student-qa__msg-role` +
  `--teacher` (uppercase letter-spaced pills that pick up the
  accent palette on teacher replies); `.student-qa__resolved-badge`
  (rounded pill on the thread head when resolved);
  `.student-qa__resolved-meta` (italic muted closing line on
  resolved threads); `.student-qa__new` + `__reply-form` (paper-
  elevated form cards with textarea + actions row);
  `.student-qa__error` (danger-tinted banner for any of the six
  error codes the actions surface). Responsive override at
  ≤560px stacks the actions row vertically and stretches the
  buttons full-width. Dark-mode overrides darken the panel +
  thread cards + lift the teacher-msg accent percentage. Brace
  balance verified: 1031 / 1031.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0). i18n parity verified: en=880, ru=880, uz=880.

Deliberately scoped out:

- **Per-block thread count badge** — the toggle button just
  reads "Ask teacher" with no count of existing threads. Adding
  a count would require either a server-side fetch on first
  paint (one per block — expensive on a multi-block page) or a
  separate "count only" endpoint. Revisit if real-world usage
  shows students missing existing threads. The student lesson
  / homework page is small enough that a curious student can
  expand each block to find a conversation.
- **Single-thread-per-block UX gate** — backend supports
  multiple threads; the new-question form is always visible,
  even with open threads on the same block. Could be tightened
  later (e.g. "you have an open question; reply there before
  asking a new one") but keeping it simple in v1 means students
  aren't blocked when their second question is unrelated to the
  first.
- **Notification of teacher reply** — student has to refresh
  the page to see new replies. Same as the admin inbox; lives
  under the "notification primitives" umbrella that hasn't been
  filed.
- **Linking to a thread from the homework / lesson list rows**
  — the student homework / lesson list pages don't yet show
  "you have N open threads here" badges. Could be a follow-up
  if the affordance feels too hidden after launch.
- **Surface inside the homework Submissions sheet** — the admin
  block-review side of this loop is Task #70: when a teacher
  has the homework Submissions sheet open or the lesson detail
  page open, mount the per-block thread overlay inline (using
  the existing `GET /api/v1/admin/classes/{cid}/{kind}/{id}/blocks/{idx}/questions`
  endpoint from #66) so they can resolve without bouncing to
  the QA tab.
- **Mention markdown / rich-text** — body is plain text. No
  bold / italic / chess board diagrams in v1.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100).
2. Sign in as `student@demo.local` / `student12345`. Open a
   homework or lesson the student is assigned. (If you don't
   have one, sign in as `admin@demo.local` first and seed
   one — see the Task #21 / #51 walkthroughs.)
3. Happy path — open a thread:
    1. Below each block on the detail page (under the homework
       attempt UI or under each lesson block), a small
       **Ask teacher** pill button now sits above a dashed
       horizontal divider.
    2. Click it. The button flips to **Hide questions**, the
       panel expands, a brief loading dash flashes, then the
       new-question form appears with the **Your question**
       label + a textarea + **Send question** primary button.
    3. Type a question ("Why did 1.e4 fail when I tried it?")
       and click **Send question**. Button briefly reads
       **Sending…**, then the panel updates inline with the
       new thread above the form. The form's **Your question**
       label flips to **Ask another question**.
    4. The thread shows: a **You** role pill at the top, the
       question body, and below it (since no replies yet) a
       reply form + **I've got it** ghost button + **Send reply**
       primary button.
4. Happy path — admin replies, student sees it:
    1. Open a second browser tab, sign in as `admin@demo.local`,
       go to `/en/admin/classes/<classId>?tab=qa`. The new
       thread appears in the inbox with "1 unresolved".
    2. Click the row, type a reply, **Send reply**. The thread
       updates with a teacher reply (accent-tinted card,
       **Teacher** role pill).
    3. Switch back to the student tab. **Refresh the page** (no
       notifications in v1). Expand the QA panel under the same
       block. The teacher's reply now renders inline below the
       student message.
5. Happy path — student follows up:
    1. Type a follow-up reply ("Got it, but what about 2.Nf3?")
       and click **Send reply**. Thread refreshes inline with
       the new student reply (paper-toned card, **You** role
       pill).
6. Happy path — student self-resolves:
    1. Click **I've got it**. Button reads **Saving…**, then
       the thread flips to a faded "resolved" treatment with a
       **Resolved** badge on the head row + an italic notice:
       "This thread is resolved. Open a new question if
       something else comes up." The reply form + resolve
       button disappear.
    2. The new-question form below stays available — a fresh
       question can still be opened.
7. Edge cases worth eyeballing:
    - **Empty body**: try to submit with whitespace only. The
      textarea's `required` HTML attribute catches it; the
      backend would also reject with 400.
    - **Network failure**: kill the API (`ctrl-C`) and try to
      send a question. Error banner appears: "Couldn't reach
      the server. Try again." Restart the API and retry — works.
    - **Resolved thread re-reply**: shouldn't happen via the UI
      (form disappears), but a crafted POST returns 409 →
      "Thread is resolved or your view is stale. Refresh."
    - **Multi-block lesson**: open a lesson with 4 blocks
      (PROSE + BOARD_WALKTHROUGH + MCQ + VIDEO). The
      **Ask teacher** pill appears under each block. Open one
      thread under block 0 and one under block 2 — they live
      independently; expanding block 0's panel doesn't show
      block 2's threads.
    - **Responsive ≤560px**: open DevTools → device toolbar →
      iPhone SE. The action row inside the new-question form
      stacks vertically with the **Send** button on top
      (full-width). The textarea stays comfortable. The QA
      panel pinches to 12px padding.
    - **Dark mode**: toggle theme. Panel background flips to a
      darker paper tint, teacher-message accent percentage lifts
      so the tinted card stays legible. Resolved-thread fade
      stays distinct.
    - **`/ru/student/...`** / **`/uz/student/...`**: all 23
      new keys pick up translations. Russian uses gendered
      copy ("Я разобрался(-ась)" on the resolve button — leaves
      both gender forms in parens). Uzbek "Tushundim" ("I got
      it") for the resolve button.
    - **Deep link**: paste `/en/student/homework/<id>` directly
      into a fresh tab. The QA pills mount on first paint
      (collapsed); expanding still lazy-fetches.
    - **Unassigned homework**: navigate to a homework the
      student isn't assigned to (crafted URL). The page itself
      404s (per the StudentHomeworkDetail guard), so the QA
      panel never renders. Same for archived homework.

### #68 — Admin Q&A inbox tab on class detail

First frontend slice of the classroom-comms umbrella. Mounts the
admin/teacher inbox as a fifth tab on `/admin/classes/[id]` —
slug `?tab=qa`, sits next to Roster / Homework / Lessons / Schedule
in the existing Phase 2c tab strip from #63. The panel is server-
rendered for first paint (the inbox preview is fetched in
`page.tsx` as a parallel batch alongside the existing eight) with
a client island for the per-row expand-to-thread interaction.

What the panel does:

- **Shows unresolved threads** in this class, oldest first, as a
  list of collapsed rows. Each row shows: parent kind + title +
  block label, asker display name, body snippet (≤200 chars), and
  reply count + last-reply meta.
- **Expand-to-thread** on click: lazy-fetches the full reply
  chain via `/api/v1/admin/classes/{classId}/questions/{qid}` and
  renders student message + ordered teacher / student replies with
  role-coloured chrome (student-default / teacher-accent fill).
- **Reply form** (visible when expanded + not resolved): textarea
  + **Send reply** button wired to a `useActionState` server
  action. On success splices the fresh thread into local state so
  the UI repaints instantly; `router.refresh()` runs in a
  transition so the inbox preview + tab badge revalidate in the
  background.
- **Mark resolved** button next to the reply form. Closes the
  thread server-side, removes the row from the inbox preview
  immediately, and refreshes.
- **Tab badge** on the strip pulls double-duty as
  "N unresolved" — the count comes from the same fetch the panel
  uses, so it's accurate without a separate endpoint.

Three deliberate scope-out decisions:

- **No "show resolved" toggle.** Inbox returns unresolved-only
  (backend is fixed on this); a future archive view can mount as
  a separate slice. Adding the toggle now would require a second
  endpoint + a "list resolved" service method, both of which can
  wait until usage shows it's needed.
- **Per-class inbox, not cross-class.** A teacher who handles
  multiple classes still has to switch class to see each inbox.
  A `/admin/inbox` cross-class roll-up would need a new backend
  endpoint that figures out "which classes can this admin
  access?" — file under future work if real-world usage shows
  classroom-coms volume warrants it. The tab badge is enough to
  surface volume per class.
- **No notifications.** Reply / new-thread events fire no email
  / push / in-app banner. v1 relies on the teacher refreshing
  the page (or the tab badge updating on next visit). Notification
  infra is its own umbrella.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/tabs.ts` —
  extended `ClassDetailTab` union with `"qa"` and added it to
  `TAB_ORDER`. Default tab unchanged (`roster`).
- `apps/web/src/app/[locale]/admin/classes/[id]/sub-nav.tsx` —
  extended `SubNavCounts` with `qa: { total, unresolved }` and
  added the `qa` arm to the `count`/`hint` derivation switch.
  Hint pill renders "N unresolved" when `unresolved > 0`,
  matching the existing homework / schedule hint shape.
- `apps/web/src/app/[locale]/admin/classes/[id]/qa-actions.ts` —
  new server actions module (~190 lines). Types mirror the
  backend DTOs verbatim: `BlockQuestionView`,
  `BlockQuestionReplyView`, `BlockQuestionThreadView`,
  `BlockQuestionInboxRow`. Three actions: `fetchThreadAction`
  (lazy detail load on expand), `replyToThreadAction`
  (`useActionState`-shaped, FormData payload, JSON.stringify
  body), `resolveThreadAction` (POST with empty body). Both
  write actions `revalidatePath` the class detail page so the
  inbox preview + count badge refresh after a mutation.
- `apps/web/src/app/[locale]/admin/classes/[id]/qa-inbox.tsx` —
  new client component. `QaInboxPanel` (outer list) + `QaInboxRow`
  (per-row collapsed/expanded state + reply form + resolve
  button). Lazy detail fetch wrapped in `useEffect` (React 19
  strict-mode bans setState in render, same pattern as the
  homework / lesson EditForm fix). Optimistic local state
  splices fresh threads on reply + drops resolved rows from the
  inbox immediately. `formatRelative(iso, locale)` helper renders
  short relative-time labels via `Intl.RelativeTimeFormat` with
  a fallback to a Tashkent-localised short date for rows older
  than a week.
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` — added
  the QA inbox to the parallel `Promise.all` fetch (now nine
  fetches), threaded `qaRows` + `qaLoadError` + `qaUnresolved`
  through to the sub-nav counts and the new panel mount under
  `{activeTab === "qa" && (...)}`. Failure on the inbox fetch
  degrades to an empty list + an in-panel error banner — the
  rest of the page keeps loading. Permission gate
  (`canMarkAttendance` — admin always, teacher only on assigned
  classes) reused for `canEdit` so unassigned teachers see the
  inbox read-only.
- `apps/web/messages/{en,ru,uz}.json` — new
  `admin.classes.qa.*` subtree (34 keys per locale, 820 → 857
  total, parity verified) plus three additions to the existing
  `admin.classes.tabs.*` subtree (`qa`, `qaUnresolved` plural,
  no other changes). Russian uses full `one/few/many/other`
  plurals on the unresolved count + reply count; Uzbek uses
  `one/other`. Error copy keys (`errorInvalid` /
  `errorUnauthorised` / `errorForbidden` / `errorNotfound` /
  `errorConflict` / `errorNetwork` / `errorUnknown`) cover all
  six paths the action union surfaces.
- `apps/web/src/app/globals.css` — appended ~280 lines under a
  new "Q&A inbox panel (Task #68 …)" header. Key primitives:
  `.qa-inbox` (column flex), `.qa-inbox__head` + `__title` +
  `__subtitle` (Newsreader title + ink-soft body), `.qa-inbox__empty`
  (dashed-border italic-muted card), `.qa-inbox__error` (danger-
  tinted banner), `.qa-inbox__list` + `__row` + `__row--open`
  (paper-elevated rows, accent-border on open),
  `.qa-inbox__row-head` (clickable button-as-row,
  `grid-template-columns: 1fr auto` collapses to single column at
  ≤560px), `.qa-inbox__thread` (paper-sunken backdrop with dashed
  top-border separator), `.qa-inbox__head-msg` + `__reply` +
  `__reply--teacher` (paper-elevated message cards with
  accent-tinted teacher variant), `.qa-inbox__msg-role` + role
  variants (uppercase letter-spaced pills), `.qa-inbox__reply-form`
  (paper-elevated card with textarea + actions row),
  `.qa-inbox__resolved-meta` (italic muted closing line). Dark-mode
  overrides darken the row + thread backdrops + lift the
  teacher-reply accent percentage. Brace balance verified:
  996 / 996.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0). i18n parity verified: en=857, ru=857, uz=857.

Deliberately scoped out:

- **Resolved / archive view** — see above.
- **Cross-class roll-up + sidebar badge** — see above.
- **Notification primitives** — see above.
- **Edit / delete on existing replies** — replies are insert-
  only by design. The data model supports edit (a future
  schema rev could add `edited_at`) but the UX is intentionally
  simple in v1.
- **Drag a question between threads / merge threads** — out of
  scope. If two students ask the same thing, the teacher
  responds to each independently (or pastes a canned answer).
- **Search inside the inbox** — not yet. The expected per-class
  thread count is small (tens, not hundreds), so a search box
  would be premature. Revisit when actual classes accumulate
  more.
- **Per-block overlay on the homework Submissions sheet / lesson
  detail** — that's Task #70. The backend endpoint exists
  (`GET /api/v1/admin/classes/{cid}/homework/{hwId}/blocks/{idx}/questions`)
  but the UI doesn't mount it yet. Teachers reach the inbox via
  the QA tab for now.
- **Mobile responsive polish below 560px** — the row head's grid
  collapses to a single column at ≤560px (chevron drops below
  the meta), but no other tightening pass yet. Should be
  reasonable on iPhone SE; a dedicated audit could land later.

Build verify: same constraint as #66 / #67 — backend Gradle build
needs the local environment to confirm. Frontend typecheck +
i18n parity + CSS brace balance all pass in the sandbox.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as `admin@demo.local` / `admin12345`. Pick a class
   the demo student is enrolled in, with at least one homework
   that has authored blocks (see Task #21's walkthrough). Save
   `CLASS_ID` from the URL.
3. Seed a question via the student endpoint (since there's no
   student UI yet — the per-block "Ask teacher" affordance is
   Task #69):
   ```bash
   STU=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"student@demo.local","password":"student12345"}' \
     | jq -r .accessToken)
   curl -s -X POST \
     "http://localhost:8080/api/v1/me/homework/$HW_ID/blocks/0/questions" \
     -H "authorization: Bearer $STU" \
     -H 'content-type: application/json' \
     -d '{"body":"Why does my move keep failing here?"}' | jq
   ```
4. Refresh `/en/admin/classes/$CLASS_ID`. The tab strip now
   shows **Q&A · 1 · 1 unresolved** as the fifth tab. Click
   it. URL becomes `?tab=qa`.
5. Happy path — inbox renders:
    1. Heading "Questions inbox" + a one-line subtitle.
    2. Below: a single collapsed row showing
       **HOMEWORK · &lt;title&gt; · Block 1**, "Asked by Demo Student",
       a body snippet, "No replies yet", and a "Show conversation"
       chevron on the right.
    3. Click the row. It expands. A short loading dash flashes,
       then the full thread renders: the student message at top
       (with **Student** role pill), no replies yet, and a
       reply form below with a textarea + **Mark resolved** +
       **Send reply** buttons.
6. Happy path — reply:
    1. Type a reply ("Try comparing to 1.d4 — what does Black
       do differently?") and click **Send reply**.
    2. Button briefly reads **Sending…**. The thread updates
       inline with the new reply (accent-tinted card with
       **Teacher** role pill). The collapsed-preview's reply
       count flips to "1 reply" and the last-reply meta line
       appears. The tab badge stays at "1 · 1 unresolved" (the
       reply doesn't resolve the thread).
7. Happy path — resolve:
    1. Click **Mark resolved**. Button reads **Resolving…**
       briefly, then the row disappears from the inbox. Tab
       badge drops to "0 · 0 unresolved" (no hint pill renders
       when count is 0).
    2. The empty-state card appears: "No unresolved questions.
       Students will be able to ask on homework and lesson
       blocks."
8. Edge cases worth eyeballing:
    - **Refresh after resolve**: the resolved thread is
      filtered out of the inbox preview server-side, so a hard
      refresh leaves the empty state in place. Single-thread
      reads still work via direct id (out-of-scope for this UI).
    - **Reply error paths**: kill the API (`ctrl-C`) and try to
      send a reply. Error banner appears: "Couldn't reach the
      server. Try again." Restart the API and retry — works.
    - **Empty body**: try to submit with whitespace only. The
      textarea's `required` HTML attribute catches it client-
      side; the backend would also reject with 400.
    - **Unassigned teacher**: sign in as a teacher who's not on
      the class roster. The QA tab still renders (read-only —
      no reply form, no resolve button). The backend enforces
      the real auth, so even a crafted POST returns 403.
    - **Multiple threads, sort order**: seed 2-3 questions
      with `sleep 1` between each (so `created_at` diverges).
      Inbox should render oldest-first.
    - **Lesson kind**: seed a question on a lesson via
      `POST /api/v1/me/lessons/$LESSON_ID/blocks/0/questions`.
      Inbox row reads **LESSON · &lt;title&gt; · Block 1**.
    - **Dark mode**: toggle theme. Row backgrounds darken,
      teacher-reply accent percentage lifts so the tinted card
      stays legible. Reply textarea + buttons inherit the
      existing dark-mode chrome.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — all 34 keys + the three tab-strip keys pick up
      translations. Russian renders the unresolved-count
      `one/few/many/other` plurals; Uzbek uses `one/other`.
      Reply count under each row reads "Пока нет ответов" /
      "Hali javoblar yo'q" when empty; "1 ответ / 2 ответа /
      5 ответов" / "1 ta javob / 5 ta javob" when populated.
    - **Deep link**: paste `/en/admin/classes/$CLASS_ID?tab=qa`
      into a fresh tab. The QA panel renders on first paint;
      no hydration flash; the tab badge is correct.

### #67 — Block-level Q&A student endpoints + test

Third backend slice of the classroom-comms umbrella. Stacks the
student self-service surface on top of #66's admin path. One DTO,
five new methods on `BlockQuestionService`, a new
`MeBlockQuestionController` mounted at `/api/v1/me`, and an
eleven-test integration suite.

Seven student endpoints land under `/api/v1/me`:

- `POST /homework/{homeworkId}/blocks/{blockIndex}/questions` and
  `POST /lessons/{lessonId}/blocks/{blockIndex}/questions` — open
  a new thread on the given block. Caller must be enrolled in the
  parent's class and the parent must not be archived (404 hides
  existence on either failure). Returns the assembled thread (head
  + empty replies) so the UI can repaint without a refetch.
- `GET /homework/{homeworkId}/blocks/{blockIndex}/questions` and
  `GET /lessons/{lessonId}/blocks/{blockIndex}/questions` — list
  the caller's own threads on this block (open + resolved, oldest
  first). Powers the "Ask teacher" affordance — if any thread
  exists the UI renders it inline, otherwise renders the open-new
  button.
- `GET /questions/{questionId}` — single thread by id, ownership
  checked. 404 hides existence on cross-student access. Unlike
  the per-block list, this works even after the parent is
  archived so historical conversations stay readable by direct
  link.
- `POST /questions/{questionId}/replies` — student posts a
  follow-up reply on their own thread. 409 on resolved (same
  one-way-close contract the teacher path uses; the student opens
  a fresh thread to continue the conversation).
- `POST /questions/{questionId}/resolve` — "thanks, I figured it
  out" self-close. 409 on already-resolved.

Three deliberate semantic choices to mirror across student +
teacher surfaces so the contract reads uniformly:

- **One-way close on both sides.** Whether the student or the
  teacher closes a thread, neither can post into it afterwards.
  Re-open is a future feature; the schema allows it.
- **Reply role is heuristic.** `BlockQuestionReplyView.authorRole`
  reads `STUDENT` for the asker, `TEACHER` for everyone else (admin,
  teacher, or even the same student with a different role mapping
  in the future). UI can colour replies by role; precise admin /
  teacher distinction would need a snapshotted role column. v1
  goes with the heuristic.
- **404 hides existence on student-side failures.** A non-enrolled
  student trying to open a thread, a student fishing for another
  student's question id, an archived-parent open / list — all
  return 404 with the same body shape. No information leak about
  whether the homework / lesson / question exists at all.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/OpenBlockQuestionRequest.java`
  — write shape `{body}` for opening a new thread. Parent kind +
  parent id + block index come from the URL so a crafted body
  can't redirect a thread to a different parent.
- `apps/api/src/main/java/com/chessedu/platform/classes/BlockQuestionService.java`
  — extended with five new methods (`openThread`,
  `listMyThreadsForBlock`, `getMyThread`, `replyAsStudent`,
  `selfResolve`) and three new private helpers
  (`loadOwnedByStudent`, `loadStudentVisibleParent`,
  `requireStudent`). Constructor gained `ClassStudentRepository`
  for the enrolment check. The student methods reuse
  `assembleThread`, `displayNamesFor`, `requireBody`, and
  `parentTitleOfRaw` from the admin slice — same
  view-construction rhythm so reply / resolve return identical
  shapes from both surfaces.
- `apps/api/src/main/java/com/chessedu/platform/classes/MeBlockQuestionController.java`
  — new controller mounted at `/api/v1/me` with class-level
  `@PreAuthorize("hasRole('STUDENT')")`. Seven routes total: two
  per-block paths each for homework + lesson (open + list), plus
  three kind-agnostic per-thread routes (read / reply / resolve)
  keyed on question id.
- `apps/api/src/test/java/com/chessedu/platform/classes/StudentBlockQuestionFlowIntegrationTest.java`
  — eleven tests. The core test (`openListReplyResolveRoundTrip`)
  exercises the full happy flow: student opens thread on
  homework block, lists it, replies, watches an admin reply land
  via the admin endpoint, fetches single-thread to confirm both
  replies are present with correct role labels (STUDENT for self,
  TEACHER for admin), and self-resolves. Plus the lesson-kind
  variant, the two 409 paths (reply-on-resolved, double-resolve),
  the four 404 paths (non-enrolled open, non-enrolled list,
  cross-student id, archived parent), the 403 role gate (admin
  token on `/me/...`), and the 400 validation paths (empty body
  on open + reply).

Build verify: same sandbox constraint as #65 / #66 —
`./gradlew :api:test --tests StudentBlockQuestionFlowIntegrationTest`
needs to run locally. Code follows the existing student-service
patterns line-by-line (`HomeworkBlockAttemptService.requireStudent`,
`HomeworkBlockAttemptService.loadStudentVisibleHomework`), so it
should compile clean.

Deliberately scoped out:

- **Frontend surface** — UI for the student "Ask teacher" button
  + thread view ships with the homework / lesson detail rework
  (separate frontend tasks, not yet filed).
- **Notifications** — out-of-band push / email / in-app banner
  when a teacher replies or when a thread is resolved on the
  student's behalf. Its own umbrella; v1 relies on the student
  refreshing the page.
- **Cross-class question listing on the student dashboard** —
  no "all my open questions across classes" surface yet. Per-block
  list is enough to make the affordance legible inside each
  homework / lesson page. If real-world usage shows students
  losing track, we can add a roll-up endpoint that mirrors the
  admin inbox's shape.
- **Edit / delete on student replies** — same insert-only
  contract as the admin path. v1 keeps replies immutable.
- **Mentions** — no `@teacher-name` syntax. Threads are 1:1
  between asker + responder by structure.
- **Per-class Q&A toggle** — same as #66; not yet a setting.

How to check as a user:

No UI yet. Verification is end-to-end via the HTTP API.

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun`. The demo seeder mints
   `admin@demo.local` and `student@demo.local` on the local
   profile.
2. Sign in as both:
   ```bash
   ADMIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"admin@demo.local","password":"admin12345"}' \
     | jq -r .accessToken)
   STU=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"student@demo.local","password":"student12345"}' \
     | jq -r .accessToken)
   ```
3. As admin, ensure the demo student is enrolled in a class with
   at least one homework + one lesson. Grab `CLASS_ID`,
   `HW_ID`, `LESSON_ID` from `/en/admin/classes`.
4. Happy path — student opens a thread on homework block 0:
   ```bash
   curl -s -X POST \
     "http://localhost:8080/api/v1/me/homework/$HW_ID/blocks/0/questions" \
     -H "authorization: Bearer $STU" \
     -H 'content-type: application/json' \
     -d '{"body":"Why does my move keep failing here?"}' | jq
   ```
   Save `Q_ID = .question.id`. Response includes the head with
   `parentKind: "HOMEWORK"`, `studentUserId` matching the
   caller, and `replies: []`.
5. Happy path — student lists their own threads on this block:
   ```bash
   curl -s "http://localhost:8080/api/v1/me/homework/$HW_ID/blocks/0/questions" \
     -H "authorization: Bearer $STU" | jq '.[] | .question.id'
   ```
   Expected: `[$Q_ID]`.
6. Happy path — student follows up:
   ```bash
   curl -s -X POST \
     "http://localhost:8080/api/v1/me/questions/$Q_ID/replies" \
     -H "authorization: Bearer $STU" \
     -H 'content-type: application/json' \
     -d '{"body":"To clarify, I tried 1.e4 e5 2.Nf3 first."}' \
     | jq '.replies[0]'
   ```
   Expected: a single reply with `authorRole: "STUDENT"` (author
   matches asker).
7. Happy path — admin replies via the admin endpoint:
   ```bash
   curl -s -X POST \
     "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/questions/$Q_ID/replies" \
     -H "authorization: Bearer $ADMIN" \
     -H 'content-type: application/json' \
     -d '{"body":"Compare to 1.d4 — the queen is more flexible."}' \
     | jq '.replies | length'
   ```
   Expected: `2`.
8. Happy path — student fetches the full thread by id:
   ```bash
   curl -s "http://localhost:8080/api/v1/me/questions/$Q_ID" \
     -H "authorization: Bearer $STU" \
     | jq '.replies | map({role: .authorRole, body: (.body[0:30] + "...")})'
   ```
   Expected: two replies, first `STUDENT`, second `TEACHER`.
9. Happy path — student self-resolves:
   ```bash
   curl -s -X POST \
     "http://localhost:8080/api/v1/me/questions/$Q_ID/resolve" \
     -H "authorization: Bearer $STU" \
     | jq '{resolvedAt: .question.resolvedAt, resolvedBy: .question.resolvedBy}'
   ```
   Expected: both fields populated, `resolvedBy` matches the
   student id.
10. Edge cases worth eyeballing:
    - **Reply on resolved → 409**: `POST .../replies` after
      step 9 → `{"code":"CONFLICT","message":"Thread is
      resolved..."}`.
    - **Double-resolve → 409**: re-run step 9 → 409.
    - **Lesson kind**: redo steps 4–6 against
      `/api/v1/me/lessons/$LESSON_ID/blocks/0/questions`. The
      response should carry `parentKind: "LESSON"`.
    - **Cross-student 404**: open a thread as `student@demo.local`,
      then sign in as a different student in the same class
      (create one via the admin invite flow; or use the test
      seed approach in `StudentBlockQuestionFlowIntegrationTest`),
      try `GET /api/v1/me/questions/$Q_ID` as the second
      student → 404.
    - **Archived parent → 404**: archive a homework via
      `DELETE /api/v1/admin/classes/$CLASS_ID/homework/$HW_ID`,
      then try to open a new thread on it as the student → 404.
      Note: a thread the student opened *before* archive is
      still readable via `GET /api/v1/me/questions/$Q_ID` (by
      direct id only — the per-block list on an archived
      parent 404s).
    - **Non-enrolled 404**: enrol another student in a
      different class (or use a fresh student with no
      memberships); their `POST /api/v1/me/homework/$HW_ID/blocks/0/questions`
      → 404.
    - **Admin token on /me/ → 403**: `POST /api/v1/me/...` with
      `$ADMIN` → 403 at the `@PreAuthorize` layer.
    - **Empty body → 400**: `POST .../questions` with
      `{"body":"   "}` → `{"code":"VALIDATION_FAILED",...}`.
      Same for empty-body replies.

### #66 — Block-level Q&A admin/teacher service + endpoints + test

Second slice of the classroom-comms umbrella. Stacks the admin /
teacher read + write surface on top of the #65 schema. Five DTOs, a
new `BlockQuestionService` mirroring the auth shape of `HomeworkService`
/ `LessonService` (SCHOOL_ADMIN / PLATFORM_ADMIN always, TEACHER only
on assigned classes), and an `AdminBlockQuestionController` mounted
under `/api/v1/admin/classes/{classId}/...`. Twelve-test integration
suite covering the inbox, per-block overlays, single-thread reads,
reply, resolve, archived-parent filter, and the auth gate.

Five endpoints land on the admin side:

- `GET /questions` — class inbox of unresolved threads on
  non-archived parents, oldest first. Each row carries a thin reply
  preview (count + last reply meta) so the UI can render without a
  per-thread fetch.
- `GET /questions/{questionId}` — full thread (head + ordered replies).
  Cross-class question id returns 404 to hide existence.
- `POST /questions/{questionId}/replies` — teacher / admin reply.
  Returns the assembled thread so the UI repaints in one round-trip.
- `POST /questions/{questionId}/resolve` — close the thread. Stamps
  `resolved_at` + `resolved_by` (paired by the V011 CHECK constraint).
  Returns the assembled thread.
- `GET /homework/{homeworkId}/blocks/{blockIndex}/questions` and
  `GET /lessons/{lessonId}/blocks/{blockIndex}/questions` — per-block
  overlays. The teacher's block-review surface (homework Submissions
  sheet, lesson detail) will mount these inline so threads are
  visible alongside the block being reviewed.

Three deliberate semantic choices worth flagging:

- **One-way close.** Replying to a resolved thread or resolving an
  already-resolved thread both return 409. The contract stays clean
  and races (two admins resolving in parallel, or replying after a
  silent resolve elsewhere) surface at the UI as "your view is
  stale, refresh." A future re-open feature can lift this — the
  schema allows it.
- **Inbox filters archived parents.** A thread on a homework that's
  been archived disappears from the inbox roll-up so a teacher
  doesn't see ghost threads on assignments they've cleaned up. The
  thread is still readable by id (single-thread endpoint) so
  historical access stays available — an audit trail more than a
  workspace.
- **Reply role is heuristic.** `BlockQuestionReplyView.authorRole`
  is `STUDENT` if the author matches the asker, else `TEACHER`. Good
  enough for v1 chrome (the typical case is "student opens, teacher
  replies"); an admin reply also reads as `TEACHER`, which is fine
  for colouring. A snapshotted role column in the schema would let
  us be more precise — file as a follow-up if the chrome ever needs
  to distinguish admin / teacher.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/BlockQuestionView.java`
  — thread head DTO with denormalised student + resolver display
  names so the inbox doesn't need a per-row user lookup.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/BlockQuestionReplyView.java`
  — single reply DTO with `authorRole` heuristic.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/BlockQuestionThreadView.java`
  — head + ordered replies + denormalised parent title. Returned by
  both the single-thread read and the reply / resolve writes so the
  UI gets the final shape in one round-trip.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/BlockQuestionInboxRow.java`
  — inbox preview (head + reply count + last-reply meta). Full reply
  chain is one click / round-trip away via the single-thread endpoint.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/CreateBlockQuestionReplyRequest.java`
  — write shape; service-layer validates non-blank + ≤2000 chars
  to surface clean `VALIDATION_FAILED` errors instead of the DB's
  generic CHECK violation.
- `apps/api/src/main/java/com/chessedu/platform/classes/BlockQuestionService.java`
  — single service for all admin / teacher Q&A reads + writes.
  Inbox roll-up batches the parent-archive check (one `findAllById`
  per kind) and the reply meta (one `findByQuestionIdInOrderByCreatedAtAsc`),
  so round-trips stay constant regardless of thread count. Reuses
  the `authoriseClass` shape from `HomeworkService` line-for-line
  (deliberate — keeps slice diffs tight; a future refactor can
  consolidate the four copies into a base helper).
- `apps/api/src/main/java/com/chessedu/platform/classes/AdminBlockQuestionController.java`
  — thin REST layer. Single class-level `@RequestMapping` lets the
  five routes share auth + the classId path variable; the two
  per-block overlay routes are nested under their respective parent
  URL shapes (`/homework/{id}/blocks/{idx}/questions` /
  `/lessons/{id}/blocks/{idx}/questions`) so the frontend can mount
  them naturally next to the existing per-parent admin endpoints.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/BlockQuestionReply.java`
  — small fix-up from #65: now carries `@EntityListeners(AuditingEntityListener.class)`
  + a setter-less `@CreatedDate` field. The previous field-init
  approach was a stale leftover; with the listener enabled the
  pattern matches `HomeworkBlockAttempt` and the rest of the
  classes module.
- `apps/api/src/test/java/com/chessedu/platform/classes/AdminBlockQuestionFlowIntegrationTest.java`
  — twelve-test suite. Direct-to-repo seeding via the autowired
  `BlockQuestionRepository` so the suite doesn't depend on the
  not-yet-built student endpoints (#67). Coverage:
  inbox happy path (two parents, oldest first, reply count = 0);
  inbox filters resolved threads but single-thread read still
  works; inbox filters archived-parent threads but single-thread
  read still works; reply succeeds + role labels as TEACHER for
  admin author; reply on resolved thread → 409; empty + over-length
  body → 400; resolve succeeds; double-resolve → 409; per-block
  homework overlay returns just-that-block threads; per-block
  lesson overlay; cross-class thread id → 404 (hides existence on
  both read and write paths); unassigned teacher → 403 vs. assigned
  teacher → 200.

Build verify: same constraint as #65 — `./gradlew :api:compileJava`
+ the integration test can't run in the sandbox (no network for
the Gradle wrapper, no system gradle / javac). Code follows the
existing `LessonService` / `HomeworkService` patterns line-by-line,
including the auth helper shape, the `AppException` factories, and
the @Transactional boundaries. Verify locally with
`./gradlew :api:test --tests AdminBlockQuestionFlowIntegrationTest`
before mounting the UI.

Deliberately scoped out:

- **Student endpoints** — `POST /api/v1/me/{homework|lesson}/{id}/blocks/{idx}/questions`
  to open a thread + `POST .../questions/{qid}/replies` to follow
  up + `GET .../questions` to list own threads + a self-resolve
  affordance ("thanks, got it"). All in Task #67. The repo + service
  layer here is shaped for it — student methods will share
  `assembleThread` / `displayNamesFor` and just add their own
  authorisation path.
- **Notifications** — out-of-band push / email / in-app banner
  when a reply lands or a thread is resolved. Its own umbrella.
- **Edit / delete on replies** — insert-only in v1 (matches the
  schema decision in #65).
- **Per-tenant Q&A toggle** — no per-class config to disable Q&A.
  Add when a tenant asks.
- **Admin / teacher write of a question on the student's behalf**
  — only students open threads in v1. If a teacher needs to start
  a discussion, they post a reply on an existing thread or use
  homework instructions / lesson prose.
- **Pagination on the inbox** — currently returns everything
  unresolved on one wire. Same shape as the homework / lesson
  list endpoints; we'll revisit when a real class accumulates
  hundreds of unresolved threads (which would be its own
  classroom-management problem).

How to check as a user:

No UI yet. Verification is end-to-end via the HTTP API.

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun`. Watch Flyway boot — V011 should
   already be applied from #65.
2. Sign in as admin (the demo seeder mints `admin@demo.local`
   on local profile). Grab the token:
   ```bash
   ADMIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"admin@demo.local","password":"admin12345"}' \
     | jq -r .accessToken)
   ```
3. Pick a class with the demo student enrolled. Grab `CLASS_ID`
   from the URL at `/en/admin/classes`. Create a homework with at
   least one BOARD block (see the Task #21 walkthrough); save
   `HW_ID`.
4. Seed a question via psql (no student endpoints yet):
   ```sql
   INSERT INTO block_question (class_id, parent_kind, parent_id,
                               block_index, student_user_id, body)
   SELECT '<CLASS_ID>', 'HOMEWORK', '<HW_ID>', 0, u.id,
          'Why did 1.e4 fail when I tried it?'
   FROM app_user u WHERE u.email = 'student@demo.local'
   RETURNING id;
   ```
   Save the returned id as `Q_ID`.
5. Happy path — inbox:
   ```bash
   curl -s "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/questions" \
     -H "authorization: Bearer $ADMIN" \
     | jq '.[] | {id: .question.id, kind: .question.parentKind, replyCount, parentTitle}'
   ```
   Expected: one row with `replyCount: 0`, the homework title, and
   the question id you just seeded.
6. Happy path — single thread:
   ```bash
   curl -s "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/questions/$Q_ID" \
     -H "authorization: Bearer $ADMIN" | jq
   ```
   Expected: the head + an empty replies array + a non-null
   `parentTitle`.
7. Happy path — reply:
   ```bash
   curl -s -X POST \
     "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/questions/$Q_ID/replies" \
     -H "authorization: Bearer $ADMIN" \
     -H 'content-type: application/json' \
     -d '{"body":"Compare to 1.d4 — what does Black do differently?"}' \
     | jq '.replies[0]'
   ```
   Expected: a single reply with `authorRole: "TEACHER"` and the
   admin's display name. The thread response includes the head
   unchanged.
8. Happy path — per-block overlay:
   ```bash
   curl -s "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/homework/$HW_ID/blocks/0/questions" \
     -H "authorization: Bearer $ADMIN" | jq 'map(.question.id)'
   ```
   Expected: `[$Q_ID]`. Try `/blocks/1/questions` if your homework
   has a second block — it returns `[]`.
9. Happy path — resolve:
   ```bash
   curl -s -X POST \
     "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/questions/$Q_ID/resolve" \
     -H "authorization: Bearer $ADMIN" \
     | jq '{resolvedAt: .question.resolvedAt, resolvedBy: .question.resolvedByDisplayName}'
   ```
   Expected: both fields populated. Inbox now reads `[]`; the
   single-thread endpoint still returns the thread.
10. Edge cases worth eyeballing:
    - **Reply to resolved → 409**: `POST .../replies` after step 9
      → `{"code":"CONFLICT","message":"Thread is resolved..."}`.
    - **Double-resolve → 409**: re-run step 9 → 409.
    - **Empty body → 400**: `POST .../replies` with
      `{"body":"   "}` → `{"code":"VALIDATION_FAILED",...}`.
    - **Cross-class 404**: pick a different `CLASS_B` id and call
      `GET /api/v1/admin/classes/$CLASS_B/questions/$Q_ID` → 404.
    - **Archived parent disappears**: archive the homework via
      `DELETE /admin/classes/$CLASS_ID/homework/$HW_ID`. Refresh
      the inbox — the question is gone. Single-thread fetch by
      `Q_ID` still works.
    - **Unassigned teacher → 403**: log in as a teacher who
      isn't on the class roster, hit the inbox URL → 403. Add
      them to the class via the roster panel and refresh → 200.

### #65 — Block-level Q&A foundation: V011 + entities + repos

First slice of the classroom-comms umbrella. Adds the persistence
layer for "students ask, teachers reply" pinned to a specific block on
a homework or a lesson. Schema lands as a single `block_question` table
keyed on a polymorphic `(parent_kind, parent_id)` pair (HOMEWORK or
LESSON) plus `block_index`, with `class_id` denormalised so the
teacher inbox can roll up unresolved threads in one index scan against
a partial index. Replies live in a separate `block_question_reply`
table — threads can grow past a single back-and-forth and inlining
them as a JSONB array on the question would chew the inbox query.
Resolution is one-way (close-only) in v1: a `resolved_at` /
`resolved_by` pair both stamp at close time, gated by a CHECK
constraint that enforces the pairing. No service / controller / DTO
in this slice — those are the next two tasks.

Two deliberate model choices worth flagging because they'll be
revisited later:

- **One table, not two.** `block_question` covers homework + lesson
  threads in one row shape. The trade-off is no real FK on
  `parent_id` (it could point at either parent table). Acceptable
  because both parents soft-delete via `archived_at` rather than
  hard-delete, so a question pointing at an archived parent stays a
  valid historical record. The win is one inbox query against a
  partial index instead of a UNION across two tables.
- **No reply edit / delete.** Replies are insert-only in v1. A
  future "edit my reply" feature lands as a soft "edited at" + a
  version row; until then `BlockQuestionReply` deliberately omits
  `updated_at` to make the immutability obvious at the entity
  boundary.

Files:

- `apps/api/src/main/resources/db/migration/V011__block_question.sql`
  — two tables, three indexes on the question table
  (`block_question_student_block_idx` covering the student-side
  read; `block_question_class_open_idx` partial WHERE
  `resolved_at IS NULL` for the inbox; `block_question_parent_block_idx`
  for the teacher's per-block overlay), one index on the reply
  table (`block_question_reply_thread_idx` keyed on
  `(question_id, created_at)` for the ordered thread read).
  CHECK constraints: `parent_kind IN ('HOMEWORK', 'LESSON')`,
  `block_index >= 0`, body non-blank + ≤2000 chars on both
  tables, plus the paired-resolution check
  `(resolved_at IS NULL) = (resolved_by IS NULL)`.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/BlockQuestionParentKind.java`
  — two-value enum (HOMEWORK / LESSON). Docstring spells out why
  we keep the table single-keyed instead of splitting per kind.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/BlockQuestion.java`
  — JPA entity. `@Enumerated(STRING)` + `@JdbcTypeCode(VARCHAR)`
  on `parentKind` mirrors the `LessonCompletionStatus` mapping.
  AuditingEntityListener handles `createdAt` / `updatedAt`.
  Service code sets explicit UUIDs at insert time (no
  `@GeneratedValue`) so the overwrite ordering trick the homework
  block-attempt path uses (#29-#35) stays available if we ever
  need it for questions.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/BlockQuestionReply.java`
  — simpler entity. Tracks only `createdAt` (no `updatedAt`) to
  signal immutability at the type boundary.
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/BlockQuestionRepository.java`
  — Spring Data JPA repo. Five derived-name queries covering the
  three primary read patterns (student-side single-block read,
  teacher per-block overlay, class-wide inbox), the per-parent-kind
  inbox split (so the service can hit homework + lesson
  separately rather than UNIONing), and a count signal for the
  admin sidebar badge.
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/BlockQuestionReplyRepository.java`
  — three derived-name queries (ordered thread read, batch read
  for inbox preview, count) + one explicit `@Modifying`-annotated
  delete-by-question for a future merge / hard-delete path. FK
  cascade does the same job, but the explicit method makes intent
  legible at call sites.

Build verify: couldn't run `./gradlew :api:compileJava` in the
sandbox (no network for the Gradle wrapper download, no system
gradle / javac). Code review confirms the JPA + Spring Data
conventions match the existing `LessonCompletion` /
`LessonCompletionRepository` patterns line-by-line, so the compile
should be clean — verify locally before booting the app.

Deliberately scoped out:

- **DTOs + service + controller** — Task #66 (admin / teacher
  endpoints) and Task #67 (student endpoints) carry those.
- **Re-opening a resolved thread** — v1 is one-way close. If a
  resolved thread needs more discussion, the student opens a
  fresh question on the same block. Re-open lands when real
  usage shows churn.
- **Notifications** — out-of-band notification (push / email /
  in-app banner) when a teacher replies / when a new question
  lands is its own umbrella. v1 inbox + a "new questions" badge
  on the admin sidebar will cover the launch.
- **Mentions / threading inside a single thread** — every reply
  is a flat sibling under the question head. No nested replies,
  no @-mentions. If a class ever runs a discussion long enough
  to need them, that's a sign we should add a forum surface, not
  shoehorn it into per-block Q&A.
- **Edit / delete on replies** — insert-only in v1.
- **Per-tenant question allowlist** — no per-class config to
  disable Q&A. If a tenant wants to turn it off (e.g. a
  competition-prep class where teachers don't want the
  distraction), that's a settings surface we'll add when asked.

How to check as a user:

No UI yet (Tasks #66 / #67 land the endpoints, then a frontend
slice mounts the surface). Verification is schema-level + sanity
inserts.

1. Start Postgres: `docker compose up -d postgres` from the repo
   root. Start the backend: `./gradlew :api:bootRun`. Watch the
   Flyway output on boot — you should see
   `Migrating schema "public" to version "11 - block question"`
   followed by `Successfully applied 1 migration`.
2. Connect to the DB
   (`docker compose exec postgres psql -U chess chess` or
   equivalent) and eyeball the new tables:
   ```sql
   \d block_question
   \d block_question_reply
   ```
   Expected: `block_question` carries `class_id`, polymorphic
   `parent_kind` + `parent_id`, `block_index`, `student_user_id`,
   `body`, the resolution pair, and audit timestamps. Three
   indexes show on `\d`. `block_question_reply` is leaner —
   `question_id`, `author_user_id`, `body`, `created_at` only,
   plus the thread index.
3. Smoke-insert a question against an existing homework + student
   (substitute real UUIDs from `homework` and `app_user` rows):
   ```sql
   INSERT INTO block_question (
       class_id, parent_kind, parent_id, block_index,
       student_user_id, body
   )
   SELECT h.class_id, 'HOMEWORK', h.id, 0, u.id, 'Why did 1.e4 fail?'
   FROM homework h, app_user u
   WHERE u.email = 'student@demo.local'
   LIMIT 1;
   SELECT id, parent_kind, block_index, resolved_at FROM block_question
     ORDER BY created_at DESC LIMIT 1;
   ```
   Expected: a single row with `resolved_at IS NULL`. Save the
   id as `Q_ID`.
4. Smoke-insert a reply:
   ```sql
   INSERT INTO block_question_reply (question_id, author_user_id, body)
   SELECT '<Q_ID>', u.id, 'Try comparing to 1.d4 — what does Black do differently?'
   FROM app_user u WHERE u.email = 'admin@demo.local' LIMIT 1;
   SELECT id, body, created_at FROM block_question_reply
     WHERE question_id = '<Q_ID>' ORDER BY created_at;
   ```
5. Verify the CHECK constraints fire as expected:
   ```sql
   INSERT INTO block_question (class_id, parent_kind, parent_id,
                               block_index, student_user_id, body)
   VALUES (gen_random_uuid(), 'QUIZ', gen_random_uuid(), 0,
           gen_random_uuid(), 'test');
   ```
   → should fail with `block_question_parent_kind_chk`.
   ```sql
   INSERT INTO block_question (class_id, parent_kind, parent_id,
                               block_index, student_user_id, body)
   VALUES (gen_random_uuid(), 'HOMEWORK', gen_random_uuid(), -1,
           gen_random_uuid(), 'test');
   ```
   → fails with `block_question_block_index_nonneg_chk`.
   ```sql
   INSERT INTO block_question (class_id, parent_kind, parent_id,
                               block_index, student_user_id, body,
                               resolved_at)
   VALUES (gen_random_uuid(), 'HOMEWORK', gen_random_uuid(), 0,
           gen_random_uuid(), 'test', now());
   ```
   → fails with `block_question_resolution_paired_chk` (resolved_at
   without resolved_by).
6. Confirm Flyway's history table is clean:
   ```sql
   SELECT version, description, success
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
   ```
   Expected: row 11 / `block question` / `true`.
7. Restart the backend. Expected: no startup errors — Hibernate's
   `validate` ddl-auto (or whatever is configured in
   application-local.yml) maps both entities cleanly. If a column
   mismatch existed between the SQL + the entity, the app would
   refuse to start with a clear pointer.
8. Roll back the smoke-test rows before moving on:
   ```sql
   DELETE FROM block_question_reply WHERE question_id = '<Q_ID>';
   DELETE FROM block_question WHERE id = '<Q_ID>';
   ```
   (The reply cascade-deletes via the FK, so the second statement
   alone is enough — but the explicit reply delete documents intent.)

### #63 — Class detail UX — tab-based IA (Phase 2c)

Closes the Option 4 UX overhaul by replacing the Phase 1 long-scroll
4-panel layout on `/admin/classes/[id]` with a `?tab=`-driven tab
strip. Roster / Homework / Lessons / Schedule each map to a slug
(`?tab=roster` is the canonical default and gets stripped from the
URL); the tab strip is sticky directly under the Phase 2a Overview
hero + Phase 2b calendar strip ("Always on top" IA shape) so the
viewer's "what's happening?" signal survives every tab switch. Only
the active panel block is rendered — the other three panel trees
are tree-shaken at render time so the page stays lean as Roster /
Homework / Lessons / Schedule each grow. Anchor IDs from Phase 1
(`#roster` / `#homework` / `#lessons` / `#schedule`) stay stable on
the panel `<div>`s and double as `aria-controls` targets so the
tablist relationship reads cleanly to screen readers; bookmarks
made during Phase 1 still resolve (a `#homework` bookmark lands on
the page; the default tab is roster so the anchor doesn't match
anything actionable, but nothing breaks). The Phase 2d day filter
(Task #64) and Phase 2b week paging (Task #62) merge their query
params into the same `searchParams` stream the tab strip writes,
so `?day=` / `?week=` survive every tab toggle and vice versa.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/sub-nav.tsx` —
  full rewrite from a hash-anchor jump-link `<nav>` into a proper
  `role="tablist"` tab strip (~163 lines). Exports
  `ClassDetailTab` (string-literal union of the four slugs),
  `normaliseClassDetailTab(raw)` (coerces an unknown / array /
  null `?tab=` value back to the default), `SubNavCounts` (kept
  nested to minimise call-site churn), and the renamed-but-
  identically-imported `ClassDetailSubNav` component. Uses
  `useSearchParams` + `useRouter` (from `@/i18n/routing`) +
  `URLSearchParams` merge to swap tabs while preserving
  `?week=` / `?day=`. `router.replace(url, { scroll: false })`
  not push — clicking a tab doesn't pollute browser history (a
  back press should return to the class list, not cycle through
  every tab the viewer toggled) and doesn't teleport scroll
  position. Default-tab clicks omit the param entirely
  (`nextParams.delete("tab")`) so a fresh URL stays canonical.
  Per-tab button: `role="tab"`, `aria-selected={isActive}`,
  `aria-controls={tab}` pointing at the existing panel anchor
  div, plus the `count` badge + the two surviving Phase 1 hint
  pills (homework "N due this week", schedule "N this week")
  carried over from the sub-nav so the viewer scans urgency at
  a glance.
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` —
  extended `PageProps` with `searchParams: Promise<{ tab?: string
  | string[] }>` (Next 15 makes both `params` and `searchParams`
  awaitables); awaited the prop, derived `const activeTab =
  normaliseClassDetailTab(sp.tab)` near the top of the component
  (next to `setRequestLocale`), passed it through to
  `<ClassDetailSubNav activeTab={activeTab}>`, and wrapped each
  of the four panel anchor `<div>`s in `{activeTab === "..." &&
  (...)}` so only the active panel renders. The Roster panel
  stays first in the JSX because its slug is the default; the
  comment block above the Homework / Lessons / Schedule panels
  (the data-hierarchy explainer from #60) stays where it was.
- `apps/web/messages/{en,ru,uz}.json` — new
  `admin.classes.tabs.*` subtree (7 keys per locale, 813 → 820
  total, parity verified): `ariaLabel`, `roster`, `homework`,
  `lessons`, `schedule`, `dueThisWeek` (plural), `scheduleThisWeek`
  (plural). Russian uses the full `one/few/many/other` plural
  set; Uzbek uses `one/other`. Inserted directly after the
  Phase 1 `subnav` subtree so the Phase 1 + Phase 2c chrome keys
  live next to each other in the file.
- `apps/web/src/app/globals.css` — appended ~125 lines under a
  new header "Class detail: tab-based IA (Phase 2c of the Option
  4 UX overhaul)". Key primitives: `.class-tabs` (sticky `top: 0;
  z-index: 30`, paper background, 1px paper-edge bottom border,
  negative horizontal margin so the strip bleeds to the page
  edge); `.class-tabs__list` (flex-wrap, list reset, 4px gap, 8px
  vertical padding, 100% width); `.class-tabs__tab` (inline-flex
  button, 8px gap between label / count / hint, 8px 14px padding,
  transparent border that's only visible on the `--on` state,
  120ms transitions); `.class-tabs__tab:hover` (paper-sunken
  tint), `:focus-visible` (2px accent ring), `--on` modifier
  (accent-soft fill + accent-tinged border + ink text);
  `.class-tabs__count` (20px circle pill, tabular-nums for
  jitter-free count transitions, paper-sunken fill, accent fill
  on `--on`); `.class-tabs__hint` (11px uppercased letter-spaced
  pill that picks up the accent tone on `--on`). Responsive
  `@media (max-width: 560px)` rule shrinks tab padding (8px →
  7px) + font (14px → 13px) and hides the hint pills (the count
  badges still render so urgency survives — the hint copy is
  redundant in the tighter layout). Dark-mode overrides bump the
  `--on` accent-fill percentages (12% → 20%) + the count
  badge's accent percentage (25% → 35%) so the active tab stays
  legible against the darker paper. CSS brace balance verified:
  955 open / 955 close.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).
i18n parity verified: en=820, ru=820, uz=820 (zero drift, up
from 813 after Task #59).

Deliberately scoped out:

- **Per-tab counts on the calendar strip dots** — when a day
  filter is pinned (Phase 2d), the dot tally on the calendar
  strip already reflects the filter. The tab badges DON'T
  re-compute under a pinned day on purpose: the badge tells
  the viewer "here's what this class has total"; the per-panel
  banner from Task #64 already announces the filtered count.
  Re-computing the badge would muddle those two signals.
- **Keyboard arrow-key navigation between tabs** — the buttons
  are reachable via `Tab` (which is enough for WCAG 2.1.1).
  A `roving tabindex` + left/right pattern would be nicer but
  adds code without changing what's surfaced. Revisit if user
  testing shows friction.
- **URL-driven tab on first paint via deep link** — works
  already because `searchParams` is read on the server too;
  pasting `?tab=schedule` into a fresh tab paints the schedule
  panel on first paint with no hydration flash.
- **Tab badge for the Past bucket on Homework / Lessons** —
  the Phase 1 collapsed `<details>` Past bucket already lives
  inside each panel; surfacing "5 overdue" on the tab itself
  would duplicate the badge that already tells the viewer
  the panel total. Revisit if real-world usage shows teachers
  wanting that signal.
- **Nested tab state on the Lessons / Homework Active /
  Archived toggle** — those toggles are panel-internal client
  state (radio-group affordance inside the panel chrome),
  not URL-driven. Conscious split: the page-level tab
  navigation lives in the URL; per-panel sub-state stays
  client-side so the URL doesn't grow unbounded.
- **Migrating Phase 1 anchor bookmarks** — a Phase 1 bookmark
  to `/admin/classes/<id>#schedule` lands on the page but on
  the default Roster tab (anchor doesn't match anything
  actionable since Schedule isn't rendered). Consciously
  accepted: a follow-up could read the URL fragment on first
  paint and translate to `?tab=schedule`, but the anchor →
  tab translation is a one-line `useEffect` that's easy to
  add later if real-world usage shows churn.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as a school admin (`admin@demo.local` /
   `admin12345`) or a teacher assigned to a class with at least
   one of each: enrolled student (so Roster has rows), upcoming
   session (so Schedule has rows), homework due this week (so
   the Homework hint pill renders), lesson with content (so
   Lessons has rows).
3. Open `/en/admin/classes/<classId>`.
4. Happy path — first paint:
    1. Below the Phase 2a Overview hero + Phase 2b calendar
       strip, the new tab strip mounts as a sticky bar with
       four buttons: **Roster · N**, **Homework · N (· N due
       this week)**, **Lessons · N**, **Schedule · N (· N
       this week)**. The hint pills only render when their
       count is > 0.
    2. The Roster tab is `--on` (accent-soft fill, ink text);
       the URL stays `/en/admin/classes/<id>` (no `?tab=`).
    3. Below the strip, only the Roster panel renders. Scroll
       down — the strip stays pinned to the top of the viewport.
5. Happy path — switch tabs:
    1. Click **Homework**. URL gains `?tab=homework`; the
       Homework panel replaces Roster below the strip; the
       Homework button picks up `--on`. The strip stays
       pinned through the swap.
    2. Click **Lessons**. URL becomes `?tab=lessons`; Lessons
       panel renders.
    3. Click **Schedule**. URL `?tab=schedule`; Schedule
       panel renders with all the Phase 1 bucket headings +
       per-week grouping intact.
    4. Click **Roster**. URL sheds `?tab=` entirely (back to
       canonical); Roster panel returns.
6. Happy path — preserve other params across tab switches:
    1. From Roster, click a day on the calendar strip that has
       at least one dot. URL gains `?day=YYYY-MM-DD` (Phase
       2d).
    2. Click **Homework**. URL becomes
       `?day=YYYY-MM-DD&tab=homework` (or
       `?tab=homework&day=...` — order isn't normative). The
       Phase 2d filter banner appears above the Homework
       panel toolbar; the panel renders only the rows that
       land on the pinned day.
    3. Click **Lessons**. Same — the day filter banner is
       still present above the Lessons panel.
    4. Click **Roster**. The Roster panel renders; no banner
       appears (Roster has no temporal axis). URL still
       carries `?day=YYYY-MM-DD` so the calendar strip's
       cell still reads pinned.
    5. Click the pinned day on the strip again to clear it.
       URL sheds `?day=` but keeps `?tab=` if you'd switched.
7. Happy path — week paging preserves tab:
    1. Open `?tab=schedule` so you can watch the Schedule
       panel update.
    2. Click the calendar strip's right arrow. URL gains
       `?tab=schedule&week=YYYY-MM-DD`. The schedule panel
       still renders the same data (the schedule isn't
       day-filtered yet); the calendar strip moves forward
       a week.
    3. Click **Homework**. URL keeps `?week=` and gains
       `?tab=homework`.
    4. Click **Jump to today** on the strip. URL sheds
       `?week=` but keeps `?tab=homework`.
8. Edge cases worth eyeballing:
    - **Crafted bad slug** — paste `/en/admin/classes/<id>?tab=garbage`
      into a fresh tab. The Roster panel paints, the Roster
      tab is `--on`, the URL still says `?tab=garbage` (no
      redirect — the page just reads the bad value as the
      default). If you click any other tab, the URL gets
      cleaned up by the merge logic.
    - **Default-tab click stays canonical** — from any other
      tab, click **Roster**. The URL drops `?tab=` entirely
      (not `?tab=roster`).
    - **Phase 1 anchor bookmark** — paste
      `/en/admin/classes/<id>#schedule` into a fresh tab. The
      page paints with the Roster tab on; the anchor doesn't
      match anything actionable (Schedule isn't rendered).
      Click **Schedule** to navigate. (Documented above as a
      conscious scope-out.)
    - **Deep link to non-default tab** — paste
      `/en/admin/classes/<id>?tab=schedule&day=2026-04-24`
      into a fresh tab. The Schedule panel paints on first
      paint with the day filter active; no hydration flash;
      the calendar strip's cell paints pre-selected.
    - **Browser back/forward** — switch tabs Roster →
      Homework → Lessons → Schedule via the strip. Press
      browser back. The URL doesn't cycle through every
      intermediate tab (because we use `router.replace` not
      push); back returns to the page that brought you to the
      class detail (e.g. `/admin/classes`).
    - **Responsive ≤ 560px** — DevTools → device toolbar →
      **iPhone SE** (375×667). The tab strip's hint pills
      hide; tab buttons compact (7px / 13px); the count
      badges still render so the urgency signal survives;
      the strip stays sticky at the top during scroll.
    - **Dark mode** — toggle theme. The active tab's
      accent-soft fill deepens (12% → 20%), the count
      badge's accent percentage lifts (25% → 35%); both
      stay legible against the darker paper.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — all 7 new tabs keys pick up their translations. The
      hint pills render Russian's full `one/few/many/other`
      forms (`1 на этой неделе` / `2 на этой неделе` /
      `5 на этой неделе`) and Uzbek's `one/other`
      (`1 ta shu hafta` / `5 ta shu hafta`). Tab labels
      (Состав / Домашние задания / Уроки / Расписание;
      Tarkib / Uy vazifalari / Darslar / Jadval) localise
      cleanly.
    - **Screen reader** — Tab into the strip. NVDA / VoiceOver
      announce "Class sections, tab list" on the `<nav>`,
      then per-tab "Roster, tab, selected" / "Homework, tab,
      not selected, …". The `aria-controls={tab}` points at
      the panel anchor div ID so the relationship is
      explicit; the tab badge is `aria-hidden="true"` so SR
      doesn't read the count twice (it's already in the tab
      label's plural-aware copy on the relevant panels).

### #59 — Lessons: responsive polish + final i18n parity pass

Audit-only slice closing out the Lessons umbrella (Tasks #49–#58).
Full responsive sweep at the narrowest target viewport (**375px,
iPhone SE**) across all four lesson surfaces — editor PROSE +
BOARD_WALKTHROUGH (Task #54), editor MCQ + VIDEO (Task #55),
viewer PROSE + BOARD_WALKTHROUGH (Task #57), viewer MCQ + VIDEO
(Task #58) — plus the student list (Task #56). **Verdict: no
gaps.** All four surfaces inherit their responsive rules from
their parent tasks exactly as Task #53's CLAUDE.md entry
predicted ("all the primitives reused here already have
responsive rules"), so no code fixes were required. The audit
confirmed each surface's critical responsive behaviours fire at
the expected breakpoints, and i18n parity across `en` / `ru` /
`uz` was re-verified one last time before closing the umbrella.

Key behaviours verified on iPhone SE (375×667):

- **Editor PROSE + BOARD_WALKTHROUGH** (Task #54, globals.css
  lines ~5617–5816). `@media (max-width: 640px)` rule stacks
  the Add-prose / Add-walkthrough footer CTAs vertically,
  collapses the annotation row's 3-col grid
  (`110px 1fr auto`) into a single column so the text
  textarea has breathing room, and flips the remove button
  to full-width. The move recorder's chip list wraps cleanly
  via `flex-wrap: wrap`.
- **Editor MCQ + VIDEO** (Task #55, globals.css lines
  ~5817–6113). `@media (max-width: 640px)` collapses the MCQ
  option row's 3-col grid (`auto 1fr auto`) into a single
  column so the remove button doesn't float disconnected
  when the input shrinks, and stacks the VIDEO meta row
  (provider pill above, hint below) vertically. The MCQ
  fieldset's dashed-border chrome stays legible at the
  tighter padding.
- **Viewer PROSE + BOARD_WALKTHROUGH** (Task #57, globals.css
  lines ~6116–6565). `@media (max-width: 840px)` rule —
  fires well before 375px — collapses the walkthrough body's
  2-col grid (`minmax(280px, 1fr) minmax(240px, 1fr)`) to a
  single column so the rail appears under the board; the
  stepper stays centered and wraps cleanly via
  `flex-wrap: wrap`. `@media (max-width: 560px)` tightens
  block padding (18px → 14px) and stacks the walkthrough
  head so the "TO MOVE" pill drops under the title.
- **Viewer MCQ + VIDEO** (Task #58, globals.css lines
  ~6565–6922). `@media (max-width: 560px)` rule collapses
  the MCQ option label's 3-col grid (`auto 1fr auto`) into
  2 columns, with the **Correct** / **Your answer** tag
  wrapping onto its own row via `grid-column: 1 / -1;
  justify-self: start` so a narrow viewport never squeezes
  the tag off the right edge. The VIDEO meta row
  (provider pill / external anchor) stacks vertically. The
  16:9 responsive frame (`aspect-ratio: 16 / 9; width:
  100%`) keeps the iframe filling the card width without
  overflow. Action button row uses `flex-wrap: wrap` so
  Submit / Try again reflow cleanly on narrow viewports.
- **Student list** (Task #56, no new CSS). Pre-verified in
  the parent task's CLAUDE.md entry ("Verified in DevTools:
  iPhone SE (375×667) renders without overflow") — the row
  card's 3-col grid inherits the homework list's existing
  responsive treatment at ≤640px (status column stacks
  under the body; chip spans the row width; Mark-as-read
  button stretches to full width).

Dark-mode overrides reviewed on the same four surfaces — the
MCQ correct / wrong fills deepen via `color-mix`, the video
frame-wrap's paper-sunken placeholder flips to the darker
variant, the invalid-URL notice's dashed-border + background
deepen one step, and the annotation rail background flips to
the darker `color-mix(in oklch, var(--paper) 88%,
var(--paper-sunken))` tone so it still reads as nested against
the dark card. All legible at the tighter viewport padding.

Files touched: **none**. This is an audit-only slice.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0).

i18n parity verified one last time: en=813, ru=813, uz=813
(zero drift — matches the post-Task #58 baseline). Russian's
full `one/few/many/other` plural forms and Uzbek's
`one/other` are in place on every plural-bearing key across
the lesson surfaces (move counts, annotation counts, block
counts, option counts, attempt counters, "N assigned · N
unread" subtitles, etc.).

Deliberately scoped out:

- **Real-device testing** — the audit was done in DevTools'
  device-emulation mode. Real-device testing on physical
  iPhone SE hardware hasn't happened; if production rollout
  surfaces any issues the emulator missed (touch target
  sizing on very narrow devices, iOS Safari quirks around
  `aspect-ratio`, etc.), file as a follow-up.
- **Landscape orientation on small phones** — 375×667
  portrait was the chosen target. The viewer's 16:9 video
  frame would consume most of the landscape viewport
  height; acceptable trade-off since classroom tablets /
  laptops sit in landscape and have plenty of headroom.
- **Widening the target to ≤320px** (feature phones /
  very-old devices) — not in scope. 375px was the
  explicitly chosen narrowest target.
- **Touch target size audits (WCAG 2.5.5)** — the Task #41
  pass already walked the student interactive board and
  admin review surface through 640/560/420px with explicit
  `min-height: 32px` floors on tap targets; the lesson
  surfaces inherit the same touch-friendly button chrome
  (ghost / primary variants) throughout. A dedicated
  tap-target audit would be a separate task if real-world
  usage turns up complaints.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev`
   (port 3100) for the frontend.
2. Sign in as a school admin (`admin@demo.local` /
   `admin12345`). Open a class with the demo student
   enrolled. Open an existing lesson with all four block
   kinds (PROSE + BOARD_WALKTHROUGH + MCQ + VIDEO), or
   create one via the editor (see Tasks #54 / #55's
   CLAUDE.md entries for full authoring walkthroughs).
3. Open DevTools → device toolbar → pick **iPhone SE
   (375×667)**.
4. **Editor surface — `/en/admin/classes/<id>`** →
   Lessons card → **Add lesson** (or **Edit** on an
   existing row):
    1. The Add-prose / Add-walkthrough / Add-mcq /
       Add-video footer CTAs stack into a vertical column
       with each button taking the full card width.
    2. Expand the BOARD_WALKTHROUGH card → scroll to
       **Annotations**. Add two annotation rows. Each
       row's 3-col grid (ply input / text textarea /
       remove) should collapse into a single-column stack
       so the textarea has breathing room for typing.
    3. Expand the MCQ card → the options fieldset's
       radio + input + remove-button row collapses to a
       single column. The **Add option** / min/max hint
       footer stays readable.
    4. Expand the VIDEO card → the provider pill appears
       above the hint line (stacked) rather than fighting
       for horizontal space. The input still takes the
       full card width.
5. **Viewer surface — `/en/student/lessons/<id>`**:
    1. The page header's breadcrumb + title + meta strip
       wraps cleanly; the class name / session pill /
       assigned timestamp row flows without overflow.
    2. Scroll to the PROSE block — paragraphs, headings,
       lists, and the code fence each respect the card
       width; code-fence long lines get horizontal scroll
       rather than breaking layout.
    3. Scroll to the BOARD_WALKTHROUGH block — the body
       collapses from 2-col to 1-col (the move/annotation
       rail appears under the board, not beside it). The
       stepper row (⏮ ◀ ▶ ⏭ + ply label) stays centered;
       the move pills wrap.
    4. Scroll to the MCQ block — each option row stays a
       single card-width row; the **Correct** /
       **Your answer** tag (after submit) wraps onto its
       own line at the bottom of the row rather than
       getting squeezed off the right edge. The feedback
       card underneath reads cleanly.
    5. Scroll to the VIDEO block — the 16:9 iframe fills
       the card width without overflow. The meta row
       underneath stacks: provider pill above, external
       **Watch on YouTube →** anchor below, each on its
       own line.
6. **Student list surface — `/en/student/lessons`**:
    1. The page header + segmented **Unread / Read** tabs
       stay legible; tab count badges wrap.
    2. Each lesson card's status column (chip + Mark-as-
       read button) stacks under the body column, chip
       spans the row width, button stretches to full
       width.
7. **Dark-mode spot-check** — toggle theme. Across all
   four surfaces the dark-mode overrides fire:
   - MCQ correct / wrong fills darken via `color-mix` so
     the tints stay legible against the darker paper.
   - VIDEO frame-wrap flips to a darker paper-sunken
     placeholder tone so the iframe-loading shimmer reads
     clearly.
   - Annotation rail background darkens so it still reads
     as nested inside the walkthrough card.
   - Invalid-block notice (dashed-border orange) darkens
     one step; the orange tint stays readable.
8. **Locale parity — `/ru/...` and `/uz/...`** — open any
   of the four surfaces at the same 375px viewport.
   Russian's longer strings (e.g. "Попробовать ещё раз
   (попытка 2)" on the MCQ retry button) should wrap
   rather than overflow their buttons; the walkthrough
   ply-label's full `one/few/many/other` plural forms
   (`1 ход из 5` / `2 хода из 5` / `5 ходов из 5`)
   render cleanly without truncation. Uzbek's `one/other`
   plurals (`1 ta tayinlangan` / `5 ta tayinlangan`) fit
   within the subtitle line without line-breaking at
   awkward boundaries.

### #58 — Student lesson viewer: MCQ + VIDEO

Second of two frontend slices for the student-side lesson
viewer, replacing the Task #57 placeholder cards for MCQ +
VIDEO with real interactive renderers. The `/student/lessons/[id]`
page gains two new block components alongside the PROSE +
BOARD_WALKTHROUGH pair that already shipped: **MCQ** renders
the authored question + 2–4 options as a radio group with a
lock-on-submit flow (radio group goes `disabled`, correct
option picks up the green tint, chosen-wrong option picks up
the red tint, optional `explanation` reveals in a paper-sunken
feedback card, **Try again** button resets both flags so the
student can retry); **VIDEO** builds an embeddable iframe `src`
from the authored URL (YouTube → `youtube-nocookie.com/embed/X`
for privacy, Vimeo → `player.vimeo.com/video/N`) inside a
responsive 16:9 `aspect-ratio` frame with `loading="lazy"` so
multi-video lessons don't chew cold-start bandwidth, a sandboxed
iframe with `strict-origin-when-cross-origin` referrerpolicy so
the embed can't leak the student's session URL to the third
party, and a meta strip carrying a **Provider · YouTube/Vimeo**
pill on the left + a **Watch on YouTube/Vimeo →** external
anchor on the right as a fallback path when the embed fails to
load (ad-blocker / network filter / corporate proxy). Both
renderers defend against crafted / legacy payloads by falling
back through a new shared **`LessonInvalidBlock`** component
that keeps the block list's visual rhythm intact (same paper-
elevated card chrome, same title slot, dashed-border orange
notice) instead of crashing the page — a safety net the
backend validator from Task #50 makes theoretically unnecessary
but pragmatically valuable the first time a legacy row surfaces.

The MCQ renderer deliberately holds all state client-side:
no server persistence of guesses, no "N of M tries remaining"
gate, no leaderboard. Lessons are revisitable study material
— completion (the `(lesson, student)` READ / NOT_STARTED / null
grain from Task #49) is the only server-persisted per-student
signal, same line the walkthrough playhead follows. The MCQ
feedback card keys off three derived flags (`isCorrect` /
`isWrong` / `submitted`) so a wrong answer without an author-
provided explanation still reads coherently — falls through
to a `lessonViewer.mcq.noExplanation` hint line rather than
leaving the feedback card empty. Screen readers get first-
class treatment: the feedback banner uses `role="status"` +
`aria-live="polite"` so a new wrong-then-correct guess
transition is announced without hijacking focus; the retry
button interpolates the attempt count into its `aria-label`
("Try again (attempt 2)") so a student who keeps missing
gets audible confirmation of progress.

The VIDEO renderer's host detection mirrors the backend
validator's allowlist from Task #50 (`youtube.com` /
`www.youtube.com` / `youtu.be` / `vimeo.com` / `www.vimeo.com`
/ `player.vimeo.com`) so any URL the backend accepted will
render a working embed. The five-bin diagnostic on the editor
side (Task #55) catches most authoring mistakes before save;
when a crafted payload somehow sneaks past, the viewer
gracefully degrades to a "Watch externally" button rather
than rendering a broken `<iframe>` with no src. The
`youtube-nocookie` hostname is a deliberate privacy-respecting
choice — YouTube's standard embed domain drops tracking
cookies on mount even before playback starts, whereas the
`-nocookie` variant waits until the student hits play. Small
footprint, outsized trust dividend with school IT.

Files:

- `apps/web/src/app/[locale]/student/lessons/[id]/detail-panels.tsx`
  — heavy extension (~300 new lines bringing the file to
  ~1,154). New `McqPayload` type + `parseMcqPayload(raw)`
  narrower that returns `null` on any shape violation
  (question empty, fewer than 2 options, non-integer
  `correctIndex`, out-of-range index, non-string option).
  New `LessonMcqView` component (~165 lines) with three
  `useState` hooks (`selected: number | null`,
  `submitted: boolean`, `attempts: number`) and two handlers
  (`onSubmit` gates on `selected != null && !submitted`,
  `onRetry` clears `selected` + `submitted` but keeps
  `attempts` for the aria-label). Fieldset-level `disabled`
  when `submitted` so the disabled state applies to every
  nested radio in one native attribute — accessible +
  zero-JS to maintain. Per-option modifier classes
  (`--correct` / `--wrong`) apply only post-submit so
  pre-submit the only cue is the radio dot. Two new
  `VideoProvider` + `VideoEmbed` types, a
  `buildVideoEmbed(rawUrl)` helper that switch-cases the
  six allowlisted hosts with appropriate path-shape checks
  (YouTube: `/^[A-Za-z0-9_-]{5,20}$/` on the `v` param or
  the `youtu.be` path first segment; Vimeo:
  `/^\d{5,15}$/` on the path first segment), and the
  `LessonVideoView` component itself (~75 lines) that
  mounts the iframe with `sandbox="allow-scripts
  allow-same-origin allow-presentation allow-popups"` and
  `allow="accelerometer; clipboard-write; encrypted-media;
  gyroscope; picture-in-picture; fullscreen"` so the
  player can enter fullscreen + use HMTL5 EME (DRM for
  Vimeo Pro). Finally a new shared `LessonInvalidBlock`
  helper component mounted before `LessonProseBlock` that's
  now used by both `LessonMcqView` (via the
  `parseMcqPayload` null fallback) and, theoretically, any
  future block kind whose parser might return null. The
  block dispatch switch at the bottom of the file now
  routes `MCQ` to `<LessonMcqView>` and `VIDEO` to
  `<LessonVideoView>` — both previously rendered the
  `LessonComingSoonBlock` placeholder, which is now only
  reachable for a theoretical fifth kind that hasn't
  shipped yet (kept as a safety net so adding a new kind
  to the backend doesn't crash the viewer before the
  frontend catches up).
- `apps/web/messages/{en,ru,uz}.json` — added 18 new keys
  per locale under the existing `student.lessonViewer.*`
  subtree: one shared `invalidBlock` key for the
  `LessonInvalidBlock` fallback copy; 11 `mcq.*` keys
  (`defaultTitle`, `choicesLegend`, `optionLabel` with
  `{index}` interpolation, `correctAnswerTag`,
  `correctAnswerAria`, `yourAnswerTag`, `yourAnswerAria`,
  `correctBanner`, `wrongBanner`, `explanationHeading`,
  `noExplanation`, `submit`, `tryAgain`, `tryAgainAria`
  with `{attempts}` interpolation — 13 total, plus 1
  reused from the shared key); 6 `video.*` keys
  (`defaultTitle`, `providerLabel`, `providerYoutube`,
  `providerVimeo`, `providerUnknown`, `invalidUrl`,
  `watchOn` with `{provider}` interpolation). Russian +
  Uzbek translations stay in lock-step with English.
  Key parity verified: en=813, ru=813, uz=813 (zero
  drift, up from 795 after Task #57).
- `apps/web/src/app/globals.css` — appended ~360 lines under
  a new header "Student lesson viewer — MCQ + VIDEO
  interactive renderers (Task #58)". Deliberately uses a
  distinct `.lesson-mcq-view*` / `.lesson-video-view*`
  namespace from the editor side (`.lesson-mcq*` /
  `.lesson-video*` in Task #55) so both surfaces can
  coexist in the build without selector collisions.
  Full `.lesson-mcq-view*` tree: `__prompt` (Newsreader
  16px/1.5 question copy), `__choices` (fieldset reset),
  `__choices:disabled` (reduced opacity + cursor default
  so the whole radio group reads as locked),
  `__choices-legend` (11px uppercase small caps),
  `__choices-list` (flex-column 10px gap, list reset),
  `__choice` (paper-elevated radio row with 1px paper-edge
  border, 12px padding, 8px radius, transition on
  `background-color` + `border-color` for smooth state
  changes), `__choice:hover:not(...)` (subtle paper-sunken
  hover that backs off post-submit via the
  `:not([class*="--correct"]):not([class*="--wrong"])`
  guard so a locked row doesn't keep lifting),
  `__choice:focus-within` (accent-ring via `box-shadow`
  so keyboard users see the current option clearly),
  `__choice--correct` (accent-soft green fill + accent
  border), `__choice--wrong` (danger-soft red fill +
  danger border), `__radio` (native radio at 18px with
  accent-color set to `--accent`), `__label` (flex-row
  laying out the option index / text / tag), `__option-index`
  (11px uppercase letter-spaced eyebrow),
  `__option-text` (15px body copy), `__option-tag`
  with `--correct` + `--wrong` variants (small rounded
  pill slot at the right edge of the row),
  `__feedback` (paper-sunken card with 1px border +
  12px padding), `__feedback--correct` + `__feedback--wrong`
  (tinted variants keyed to the two banner moods),
  `__feedback-head` (15px/600 weight headline),
  `__feedback-eyebrow` (11px uppercase label above the
  explanation), `__feedback-body` (15px/1.5 Newsreader
  for the author's explanation), `__feedback-body--hint`
  (italic muted variant for the "no explanation" fallback),
  `__actions` (flex-row right-aligned button slot).
  Full `.lesson-video-view*` tree: `__frame-wrap`
  (responsive 16:9 container via `aspect-ratio: 16 / 9`
  with paper-sunken fill as an iframe-loading placeholder
  + 10px radius + `overflow: hidden` so the iframe's
  corners clip cleanly), `__frame` (absolutely-sized
  iframe filling the wrap, 0 border), `__invalid`
  (dashed-border orange-tinted notice for the
  unknown-host / unparseable-URL fallback), `__meta`
  (flex-row justify-between carrying the provider pill +
  the external watch link), `__provider` (paper-sunken
  pill with eyebrow + name variants), `__provider-eyebrow`
  (11px uppercase muted label), `__provider-name`
  (13px/500 weight), `__external` (accent-ink link with
  dashed-underline-on-hover treatment for the
  "Watch on YouTube/Vimeo →" fallback anchor). Responsive
  `@media (max-width: 560px)` rule stacks the MCQ option
  label (index / text / tag drop to a single column so
  narrow viewports don't squeeze the tag off the right
  edge) + stacks the video meta row (provider pill above,
  external link below). Dark-mode overrides: MCQ
  `__choice--correct` + `__choice--wrong` fills darken
  one step via `color-mix` so the tints stay legible
  against the darker paper; `__option-tag--correct` +
  `__option-tag--wrong` + `__feedback--correct` +
  `__feedback--wrong` get matching darker variants; the
  video frame-wrap flips to a darker paper-sunken
  placeholder tone; the invalid-URL notice's
  dashed-border + background deepen one step. CSS brace
  balance verified.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).

Deliberately scoped out:

- **Server-side persistence of MCQ guesses** — the backend
  `LessonCompletion` table carries READ / NOT_STARTED / null
  at the `(lesson, student)` grain, not per-block. Persisting
  per-block MCQ guesses would require a new
  `lesson_mcq_attempt` table, a new endpoint, and a new
  view model — all for a signal with no clear consumer in
  v1 (the admin completion sheet doesn't have a per-block
  column). Revisit if teachers ask for "who's stuck on
  which question" analytics.
- **Attempt-limiting / rate-limiting** — the MCQ renderer
  lets the student retry indefinitely. A "3 tries then the
  correct answer reveals" escape hatch is doable client-side
  but hasn't come up as a curriculum ask; would also need
  a scoped-out `maxAttempts` field on the authored payload
  (Task #55 editor doesn't expose one).
- **Inline video playhead persistence** — same reason as
  the walkthrough playhead in Task #57: no server slot,
  client can re-seek on revisit.
- **Video captions / subtitle track toggle** — YouTube
  + Vimeo both expose caption controls inside their own
  player chrome. Re-implementing them would be work for
  little gain.
- **Autoplay / loop / start-at-timestamp params** — the
  embed URL doesn't pass `autoplay=1` (hostile on
  classroom tablets), `loop=1` (usually wrong for
  instructional videos), or a `start=N` timestamp (no
  backend slot to store one). If curriculum authors
  start asking for timestamped clips, that's a schema
  extension on the VIDEO block payload (Task #55 editor
  + Task #58 viewer both update).
- **Transcript / chapter markers** — same schema reason
  as above.
- **Preloading embed iframes** — `loading="lazy"` is the
  only preload hint. A lesson with 10 videos would only
  materialise the iframes as the student scrolls toward
  them. If real-world usage shows flash-of-empty-placeholder
  is annoying, we could switch to `loading="eager"` for
  the first video only; for now the cold-start bandwidth
  saving wins.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100)
   for the frontend.
2. Sign in as a school admin (`admin@demo.local` /
   `admin12345`) or a teacher assigned to a class. Open
   a class that has the demo student enrolled. Click
   **Add lesson**; in the lesson editor (Tasks #54 /
   #55) add all four block kinds so the viewer renders
   a coherent mix: a PROSE block with a short paragraph;
   a BOARD_WALKTHROUGH block with 3–5 moves (so the
   Task #57 stepper still renders alongside the new
   kinds); an MCQ block with 4 options, a correct index,
   and an authored `explanation` string; a VIDEO block
   with a canonical YouTube URL
   (`https://www.youtube.com/watch?v=dQw4w9WgXcQ` works
   fine for a smoke test). Save.
3. Log out, sign in as `student@demo.local` /
   `student12345`. Navigate to **Lessons** in the
   sidebar, click the newly-created lesson's row.
4. Happy path — MCQ first guess correct:
    1. Scroll to the MCQ block. The question copy
       renders in Newsreader 16px; the four options sit
       underneath as a radio-group fieldset with an
       "ANSWER CHOICES" legend.
    2. Each option row is paper-elevated with a
       "OPTION 1" / "OPTION 2" / etc. index eyebrow +
       the author's option text. Hover any row — the
       background subtly tints.
    3. Click the radio for the correct option. The
       **Submit answer** button at the bottom-right of
       the block enables.
    4. Click **Submit answer**. The fieldset locks
       (radios go disabled). The chosen row picks up
       the accent-soft green fill + accent border + a
       small **Correct** tag slot on the right edge.
       The other rows stay visually neutral. A
       feedback card appears under the choices reading
       **Nice work — that's correct!** in accent-soft
       green, with an **EXPLANATION** eyebrow + the
       author's explanation text underneath. The
       Submit button is replaced by a **Try again**
       ghost button.
    5. Click **Try again**. The fieldset re-enables,
       the radio clears, the green tint + tag + feedback
       card disappear. The Submit button reappears
       disabled (no selection).
5. Happy path — MCQ first guess wrong:
    1. Select a wrong option. **Submit answer**.
    2. The chosen-wrong row picks up a danger-soft red
       fill + danger border + a small **Your answer**
       tag; the correct row simultaneously picks up
       the green fill + **Correct** tag so the student
       sees which one was right. The feedback card
       reads **Not quite — the correct answer is
       highlighted below.** in red, followed by the
       author's explanation if present.
    3. Click **Try again**. Retry — this time pick the
       correct option — green happy path again.
6. Happy path — VIDEO embed:
    1. Scroll down to the VIDEO block. The responsive
       16:9 frame renders a YouTube iframe inline. The
       embed URL in devtools' Network tab should point
       at `youtube-nocookie.com/embed/dQw4w9WgXcQ`, not
       `youtube.com/embed/...` (cookie-free variant).
    2. Under the frame, the meta row shows
       **PROVIDER · YouTube** on the left and
       **Watch on YouTube →** as a dashed-underline-
       on-hover anchor on the right. Click the anchor
       — opens the canonical watch URL in a new tab
       with `rel="noopener noreferrer"`.
    3. Click the video's play button in the iframe —
       playback starts; the student can use YouTube's
       native fullscreen control. (HTML5 fullscreen
       works because `allowFullScreen` + the `allow`
       attribute carry `fullscreen`.)
7. Edge cases worth eyeballing:
    - **Invalid MCQ payload** — seed an MCQ block with
      only 1 option via the admin HTTP API (the editor
      blocks this client-side; the backend also
      validates on write, so this only reproducibly
      happens for legacy corrupted rows). If one ever
      surfaces, the viewer renders the shared
      `LessonInvalidBlock` card with the dashed-border
      orange notice reading **This block couldn't be
      rendered — ask your teacher to check it.** rather
      than crashing the page.
    - **Empty MCQ explanation** — create a second MCQ
      block with `correctIndex` set and **explanation
      left blank**. Guess wrong → the feedback card
      reads **Not quite — the correct answer is
      highlighted below.** followed by an italic muted
      hint line **Ask your teacher for a walkthrough
      of this one.** in place of the explanation body.
      Guess correctly on retry → no explanation block
      renders (correct + no explanation = just the
      green banner, no empty slot).
    - **Keyboard navigation (MCQ)** — Tab into the
      block. `Tab` cycles through the radios; `Space` /
      arrow keys pick an option. `Tab` to **Submit**,
      `Enter` submits. After submit, `Tab` lands on
      **Try again** directly because all the radios
      are disabled.
    - **VIDEO bad URL** — seed a VIDEO block with a URL
      the backend accepts but the frontend's stricter
      path regex rejects (e.g. a YouTube playlist URL
      like `https://www.youtube.com/playlist?list=PLxxxxxx`).
      The frame-wrap is replaced by a dashed-border
      orange notice reading **We can't embed this
      video. Open it externally with the link below.**;
      the meta row below still shows
      **PROVIDER · YouTube** + the **Watch on YouTube →**
      external anchor so the content is still reachable
      with one click.
    - **VIDEO empty URL** — seed a payload with
      `url=""` (again, only via crafted API call — the
      editor's `required` HTML attribute catches this
      client-side). The fallback copy renders without
      the external anchor (nothing to link to).
    - **Multiple videos on one lesson** — create a
      second VIDEO block with a different URL. On
      first paint, only the first iframe loads; scroll
      to the second and watch the network tab — the
      second iframe's `src` request fires only then
      (`loading="lazy"` in action).
    - **Ad-blocker / network filter** — disable
      JavaScript / block `*.youtube-nocookie.com` via
      uBlock Origin. The iframe renders but stays
      blank / gets replaced by the ad-blocker's
      placeholder. The **Watch on YouTube →** link
      still works because it's a plain `<a>`.
    - **Responsive ≤ 560px** — DevTools → device
      toolbar → **iPhone SE** (375×667). The MCQ
      option label stacks: the index eyebrow above,
      the option text below, the **Correct** /
      **Your answer** tag on its own line underneath
      so the tag never gets squeezed off the right
      edge. The video meta row stacks: provider pill
      above, external link below — each gets its own
      row so neither overlaps the other. The iframe
      keeps its 16:9 aspect ratio and fills the card
      width.
    - **Dark mode** — toggle theme. MCQ
      `--correct` + `--wrong` fills deepen via
      `color-mix` so the tints stay legible against
      the darker paper. The `Correct` / `Your answer`
      tags pick up darker fills. The feedback card's
      accent-soft-green / danger-soft-red tints
      darken one step. The video frame-wrap's
      paper-sunken placeholder tone flips to the
      darker variant so the iframe-loading shimmer
      reads against the dark card.
    - **`/ru/student/lessons/<id>`** — all 18 new keys
      pick up Russian translations. Option labels
      render as **ВАРИАНТ 1** / **ВАРИАНТ 2** with
      the `{index}` interpolation; the correct-answer
      tag reads **Правильно**; the wrong-answer tag
      **Ваш ответ**; the feedback banners render as
      **Молодец — правильно!** / **Не совсем — правильный
      ответ выделен ниже.**; the try-again button's
      `aria-label` interpolates the Russian attempt
      count (**Попробовать ещё раз (попытка 2)**). The
      video provider pill reads **Провайдер · YouTube**;
      the external link **Смотреть на YouTube →**.
    - **`/uz/student/lessons/<id>`** — same treatment
      in Uzbek. Option labels render as **VARIANT 1**;
      the correct-answer tag **To'g'ri**; feedback
      banners **Zo'r — to'g'ri javob!** /
      **Unchalik emas — to'g'ri javob quyida
      ko'rsatilgan.**; provider pill
      **Provayder · YouTube**; external link
      **YouTube'da ko'rish →**.
    - **Deep link** — paste `/en/student/lessons/<id>`
      into a fresh tab. All four block kinds render on
      first paint (PROSE + BOARD_WALKTHROUGH + MCQ +
      VIDEO in authored position order). The MCQ + VIDEO
      client islands hydrate without flash because the
      blocks' outer shells (card chrome + title) are
      server-rendered; only the interactive state
      (radio selection / submit lock / embed) is
      client-side. The lesson completion status card
      at the top still renders the three action
      buttons (Mark as read / Mark as not started /
      Clear) from Task #57 unchanged.

### #57 — Student lesson viewer: PROSE + BOARD_WALKTHROUGH

First of two frontend slices for the student-side lesson viewer,
wiring up `/student/lessons/[id]` as the detail page counterpart
to the Task #56 list. Mounts a page that mirrors the homework
detail's auth gate / role gate / header chrome line-for-line (the
lesson-specific meta strip swaps the homework due-pill for an
optional "Session · 20 Apr 18:00" pill; the rest of the breadcrumb
+ title + assigned / marked timestamps are identical) and hands
off to `StudentLessonDetailPanels` which owns the per-block
render switch. Two of the four v1 block kinds land in this slice:
**PROSE** renders the authored markdown through a deliberately
narrow safe-markdown path (paragraph splitting + heading /
list / code-fence detection, no inline HTML) so the viewer can
stay a server component without needing a full rehype pipeline,
and **BOARD_WALKTHROUGH** renders the authored position + move
list + annotations with a `<Chessboard>`-backed stepper that the
student can drive via ◀ / ▶ / ⏮ / ⏭ buttons, a clickable UCI
move-list rail, and — when annotations are present — a parallel
annotations rail where each row is a click-to-jump button that
seeks the board to that ply. MCQ + VIDEO cards render a
dashed-border "coming soon" placeholder so authors who seed a
lesson with all four kinds today see every block represented;
Task #58 replaces those placeholders with real interactive MCQ
+ embedded video players. Completion state management
(mark-read / mark-not-started / clear) is reused verbatim from
the list's `finishLessonAction` via a three-button action row in
the status card at the top of the page, so a student who's
working through a long lesson can self-mark without scrolling
back to the list.

Files:

- `apps/web/src/app/[locale]/student/lessons/[id]/page.tsx` —
  new server component (~157 lines). Follows the homework
  detail twin's shape: auth gate via `getAccessToken` → `/me`
  fetch → STUDENT role check → `/api/v1/me/lessons/{id}` fetch
  → 401/403 session-logout / 404 friendly empty-state copy
  → render. Uses `formatInstant` locked to `Asia/Tashkent`
  for the session / assigned / marked timestamps so a student
  in Tashkent reads the wall-clock regardless of the browser's
  local tz. Header renders the breadcrumb link back to
  `/student/lessons`, the lesson title, and a meta strip with
  the class name + optional session pill + assigned + marked
  timestamps before handing off to the panels component.
- `apps/web/src/app/[locale]/student/lessons/[id]/detail-panels.tsx`
  — new client component (~854 lines). Exports
  `StudentLessonDetail` (mirrors backend DTO), the default
  `StudentLessonDetailPanels` component, and supporting
  subcomponents. `LessonProseBlock` parses the authored
  markdown via a pure helper that splits on double-newlines,
  detects headings (`#` through `######`), bullet / numbered
  lists, and triple-backtick code fences, then renders each
  segment through a kind-specific class (`.lesson-prose__p`,
  `.lesson-prose__h`, `.lesson-prose__list`,
  `.lesson-prose__code`). Safe-by-default: nothing renders as
  raw HTML; everything goes through a plain `{text}` interpolation
  so a malicious / accidental `<script>` in the authored markdown
  is inert. `LessonBoardWalkthroughView` is the stepper: reads
  `startingFen` + `moves[]` from the block payload, uses
  `chess.js` to precompute the per-ply FEN array, holds the
  current `ply` in state, drives the `react-chessboard` v5
  `<Chessboard>` with `allowDragging: false /
  allowDrawingArrows: false` so the viewer is strictly
  read-only. Renders a side-indicator pill ("TO MOVE:
  WHITE / BLACK") on the head, the board under
  `.lesson-walkthrough__board-wrap` paper-sunken frame, a
  stepper row underneath with four buttons + a
  ply-label ("Move 3 of 12"), and a two-row rail: the UCI
  move-list (each move a click-to-seek pill) and — only when
  `annotations` is non-empty — the annotations list with a
  dashed top-border separator. Each annotation is a row card
  with an uppercase-small-caps click-to-seek anchor
  ("START POSITION" / "AFTER MOVE 3") and the annotation text
  rendered through Newsreader for readability; the anchor
  button syncs the board to that `ply` and the matching
  move-list chip picks up the `--active` highlight.
  `LessonComingSoonBlock` is the placeholder for MCQ + VIDEO
  kinds: a dashed-border card with the kind tag + "Coming
  soon" copy so a lesson seeded with all four kinds
  renders coherently today. `LessonInvalidBlock` is the
  fallback for a block whose payload doesn't parse cleanly
  (e.g. a BOARD_WALKTHROUGH with an illegal FEN the backend
  somehow let through) — dashed orange-tinted notice with
  the block kind + an error hint.
- `apps/web/messages/{en,ru,uz}.json` — added the
  `student.lessonDetail.*` subtree (18 keys covering the
  breadcrumb / not-found copy / meta labels / status pill
  / action row buttons / error strings) and the
  `student.lessonViewer.*` subtree (25 keys covering the
  viewer heading / empty / invalid / coming-soon / walkthrough
  side indicator / stepper button labels / ply-label /
  rail headings / annotation anchor copy / prose code hint).
  Russian uses full `one/few/many/other` plurals on the
  `movesCount` key (e.g. "1 ход / 2 хода / 5 ходов"); Uzbek
  uses `one/other`. Key parity verified:
  en=795, ru=795, uz=795 (zero drift).
- `apps/web/src/app/globals.css` — appended ~460 lines under
  a new header "Student lesson detail viewer (Task #57 —
  PROSE + BOARD_WALKTHROUGH)". Two new status-pill variants
  slot in next to the existing homework-detail pill styles:
  `.student-hw-detail__status-pill--unmarked` (neutral
  paper-sunken fill for `status === null`) and
  `.student-hw-detail__status-pill--read` (accent-soft green
  tint matching the list's READ chip). Semantic
  `.student-lesson-detail` hook added for per-page overrides
  without affecting homework. Full `.lesson-viewer*` block
  chrome: `__list` (flex column), `__empty` (dashed-border
  paper-elevated card with italic muted copy for the
  zero-blocks lesson case), `__block` (paper-elevated card
  with 14px radius + 18px padding), `__block-title`
  (Newsreader 17px/500), `__invalid` (dashed orange-tinted
  notice with warm-tinted background), `__soon` (dashed
  muted notice for MCQ/VIDEO placeholders), and
  `__block--unknown` (dashed-border fallback with slightly
  darker paper tone). Full `.lesson-prose*` primitives:
  `.lesson-prose` (flex-column 12px gap, Newsreader
  15px/1.65), `__p` (pre-wrap paragraph), `__h` with a
  size cascade (h3=18px serif, h4=16px serif, h5=15px
  uppercased letter-spaced, h6=14px uppercased muted so the
  heading hierarchy scans from visually loudest to softest),
  `__list` (indented gap list), `__code` (paper-sunken pre
  block with JetBrains Mono + overflow-x auto so long lines
  don't chew the column), `__empty` (italic muted fallback
  when the authored markdown is blank). Full
  `.lesson-walkthrough*` primitives: `__head` (flex-wrap
  justify-between for title + side indicator),
  `__side` (pill with uppercase "TO MOVE" label),
  `__body` (CSS grid `minmax(280px, 1fr) minmax(240px, 1fr)`
  so the board and the rail share the card width on desktop
  and collapse to stacked columns on narrow viewports),
  `__board-wrap` (paper-sunken pad with 1px shadow so the
  board reads as a framed wooden board against the card),
  `__stepper` (flex-wrap centered row of four navigation
  buttons), `__ply-label` (JetBrains Mono 13px, 7rem
  min-width so the label doesn't jitter as move count
  transitions), `__rail` (flex-column with a subtle
  `color-mix(in oklch, var(--paper) 94%, var(--paper-sunken))`
  tint so the rail reads as nested inside the card),
  `__rail-heading` (11px uppercased small caps), a
  `--second` modifier that adds a dashed top-border
  separator for the annotations heading when both rails
  render, `__move-list` (flex-wrap reset list, 4px gap),
  `__move` (small mono pill button with hover + focus-visible
  states), `__move--active` (accent-soft tint marking the
  current ply), `__annotations` (flex-column 8px gap),
  `__annotation` (paper-elevated row card), `__annotation--active`
  (accent-soft highlight syncing with the matching move pill),
  `__annotation-anchor` (uppercase small-caps button style
  for the click-to-seek anchor), `__annotation-text`
  (Newsreader 15px/1.5 pre-wrap). Responsive
  `@media (max-width: 840px)` stacks the walkthrough body
  to a single column so the rail appears under the board
  on tablet + narrow laptops;
  `@media (max-width: 560px)` tightens block padding
  (18px → 14px) and stacks the walkthrough head so the side
  indicator lives under the title on mobile. Dark-mode
  overrides: `.lesson-walkthrough__rail` rail background
  flips to `color-mix(in oklch, var(--paper) 88%,
  var(--paper-sunken))` so it stays visually nested against
  the darker card; `.lesson-walkthrough__board-wrap` shadow
  deepens one step; `.lesson-viewer__block--unknown`
  dashed border / fill pick up the darker paper tones.
  CSS brace balance verified (open/close count matches).

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).

Deliberately scoped out:

- **MCQ + VIDEO viewers** — Task #58. MCQ needs an interactive
  radio-group that locks on submit + reveals the `correctIndex`
  + optional `explanation` after the guess is recorded (plus a
  retry affordance since lessons are revisitable). VIDEO needs
  an embed rewrite from the canonical YouTube / Vimeo URL to
  the provider's iframe-embeddable form + a privacy-respecting
  sandbox. Both are non-trivial interactive surfaces that
  deserve their own review pass — the Task #55 editor already
  persists the content authors write, so #58 just owns the
  player side.
- **Per-block persistence of student state** — MCQ guesses,
  walkthrough playhead, video playhead all stay on the client
  in v1. Lesson completion (the `(lesson, student)` READ /
  NOT_STARTED / null grain from Task #49) is the only
  server-persisted per-student signal. Persisting per-block
  interaction would double the lesson write footprint and
  complicate the admin sheet for no clear benefit in v1 — the
  student can just scroll back and re-interact.
- **Rich-text annotations** — annotations are plain strings
  (≤200 chars). No bold / italic / board shapes / arrow
  overlays on the board. If curriculum authors ask for richer
  annotation content, a future slice can reuse the
  `LessonProseBlock` parser path on each annotation.
- **Board arrows / shapes on walkthrough positions** — the
  viewer doesn't paint a "best move" arrow or highlight
  squares at the current ply even if the author might want
  to. Same reason as above: the v1 BOARD_WALKTHROUGH payload
  doesn't carry that data. A future schema extension +
  player-side renderer can layer it in.
- **Keyboard shortcuts on the stepper** — the four buttons are
  reachable via `Tab`; no `←` / `→` / `Home` / `End` keyboard
  shortcuts on the board wrap. File as a polish follow-up if
  real-world testing shows students want it.
- **Auto-mark as read on scroll-to-end** — completion stays
  an explicit action via the buttons at the top. A passive
  auto-mark feels wrong because a student can land on the
  detail page for a 30-second look without actually reading.
  Revisit if teachers report students forgetting to mark.
- **Table / image support in PROSE markdown** — the
  safe-markdown parser handles paragraphs, headings, lists,
  and code fences. Tables (GFM pipe syntax) and inline images
  are out of v1 — if authors need them we add to the parser
  (or swap to a real rehype pipeline with
  `rehype-sanitize`).

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100)
   for the frontend.
2. Sign in as a school admin (`admin@demo.local` /
   `admin12345`) or a teacher assigned to a class. Open a
   class that has the demo student enrolled. Click **Add
   lesson**; in the lesson editor (Tasks #54 / #55) add all
   four block kinds: a PROSE block with a few paragraphs +
   a `## Heading` + a bulleted list + a code fence; a
   BOARD_WALKTHROUGH block with the starting position, 3–5
   moves (e.g. `1.e4 e5 2.Nf3 Nc6 3.Bc4`), and 2–3
   annotations at distinct plies; an MCQ block; a VIDEO
   block. Save.
3. Log out, sign in as `student@demo.local` /
   `student12345`. Navigate to **Lessons** in the sidebar
   — the list card shows the new lesson in the **Unread**
   tab.
4. Happy path — open the detail page:
    1. Click the lesson row. The detail page loads with the
       breadcrumb **← Back to lessons** above the title in
       Newsreader serif, and a meta strip underneath the
       title showing the class name + optional session pill
       ("Session · 20 Apr 18:00") + assigned timestamp + (if
       already marked) marked timestamp.
    2. Under the title, a paper-elevated status card shows
       the current status pill (neutral "Unmarked" on first
       load) with three action buttons on the right: **Mark
       as read**, **Mark as not started**, **Clear**.
5. Happy path — PROSE block:
    1. Below the status card, under the "BLOCKS" heading,
       the PROSE block renders first. Body copy is
       Newsreader 15px/1.65 with comfortable line-height;
       the `## Heading` renders as an 18px serif heading,
       the bulleted list indents and gets proper gap
       spacing, the code fence renders on a paper-sunken
       pre block with JetBrains Mono.
    2. Long lines in the code fence should get a horizontal
       scroll rather than overflowing the card.
6. Happy path — BOARD_WALKTHROUGH block:
    1. The walkthrough block follows, with its title on the
       left and a **TO MOVE: WHITE** pill on the right of
       the head.
    2. The board renders inside a paper-sunken frame under
       the head. Below the board, four navigation buttons
       (⏮ ◀ ▶ ⏭) sit in a stepper row with a move-label in
       the middle reading **"Move 0 of 5"** (or similar).
    3. To the right of the board (on a ≥840px viewport),
       the rail shows two stacked sections. The first is
       **MOVES** with all recorded UCI tokens as small
       mono pills; the second, separated by a dashed
       top-border, is **ANNOTATIONS** with each annotation
       as a row card.
    4. Click **▶** — the board animates to the next
       position, the move label updates to **"Move 1 of
       5"**, the matching move pill in the rail picks up
       the accent-soft `--active` highlight.
    5. Click directly on **"Move 3"** in the move rail.
       The board jumps to the position after the third
       move; the move pill highlights; the annotation row
       whose anchor points to ply 3 (if any) picks up the
       `--active` highlight too.
    6. Click on an annotation's anchor chip (e.g. **AFTER
       MOVE 3**). The board seeks to that ply; same visual
       sync on the move rail.
    7. Click **⏮** — board returns to the starting
       position; the **TO MOVE** pill reads **WHITE** again
       (it flips each ply).
7. Happy path — MCQ + VIDEO placeholders:
    1. Below the walkthrough, the MCQ block renders as a
       dashed-border card with the kind tag (**Quiz
       question**) + "Coming soon — this block kind isn't
       playable yet." copy.
    2. The VIDEO block renders the same placeholder pattern
       with its own kind tag.
8. Happy path — completion mark:
    1. Scroll back up to the status card.
    2. Click **Mark as read**. The pill flips to the
       accent-soft green **Read** variant; the action row
       contracts to show only the opposite actions (Mark
       as not started / Clear); the meta strip's marked
       timestamp appears.
    3. Click **Clear**. Pill returns to the neutral
       **Unmarked** state; marked timestamp disappears.
    4. Click **Mark as not started**. Pill flips to the
       amber **In progress** / **Not started** variant; the
       marked timestamp reappears (a NOT_STARTED write also
       refreshes `markedAt` so the admin sheet can show
       "last touched at").
9. Edge cases worth eyeballing:
    - **Empty lesson** — create a lesson with no blocks.
      Open the detail page. The viewer shows
      `.lesson-viewer__empty` — a dashed-border italic
      muted card with "This lesson has no content yet."
      copy. The status card + meta strip still render so
      the student can still mark-read.
    - **Invalid block payload** — seed a
      BOARD_WALKTHROUGH block with an illegal FEN via the
      admin HTTP API (the editor blocks this client-side;
      the backend also validates on write, so in practice
      this shouldn't happen for new rows). If an old row
      has corrupt data somehow, the block renders as the
      orange-tinted `.lesson-viewer__invalid` card with
      the kind tag + "This block couldn't be rendered."
      copy rather than crashing the page.
    - **Unknown block kind** — a block whose kind isn't
      one of PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO
      (possible if a future kind ships on the backend
      before this viewer learns about it) renders as the
      `.lesson-viewer__block--unknown` fallback with a
      muted-tone card + kind tag copy.
    - **404 — out-of-class lesson** — paste
      `/en/student/lessons/<uuid-from-another-class>` into
      the URL bar. The page renders the breadcrumb + a
      "Lesson not found" friendly card. Same copy as the
      genuinely-missing UUID case (the backend hides
      existence on purpose, per Task #52).
    - **Load failure** — kill the API (`ctrl-C` the
      backend) and refresh the page. The auth `me` fetch
      fails → redirect to login. Restart the API and
      sign in again.
    - **Responsive ≤ 840px** — DevTools → device toolbar
      → **iPad Mini** (768×1024). The walkthrough body
      collapses from two columns to one; the rail
      appears below the board; the stepper stays
      centered; the move-list pills wrap cleanly.
    - **Responsive ≤ 560px** — switch to **iPhone SE**
      (375×667). Block cards tighten to 14px padding,
      the walkthrough head stacks (title above, side
      pill below); the stepper stays readable; the
      board fills the card width.
    - **Dark mode** — toggle theme. The walkthrough rail
      background flips to the darker `color-mix` tone so
      it still reads as nested; the board frame's drop
      shadow deepens; the status card + action buttons
      pick up the existing dark-mode treatment; the
      accent-soft `--active` highlight on move pills +
      annotation rows stays saturated against the darker
      paper.
    - **`/ru/student/lessons/<id>`** / **`/uz/student/lessons/<id>`**
      — all 43 new keys pick up translations. The
      walkthrough ply-label renders Russian's full
      `one/few/many/other` plural forms ("1 ход из 5" /
      "2 хода из 5" / "5 ходов из 5") and Uzbek's
      `one/other` ("1 ta yurish / 5 ta yurish"). The
      TO MOVE pill's side labels localise
      (**БЕЛЫЕ** / **ЧЁРНЫЕ** in Russian,
      **OQ** / **QORA** in Uzbek). Annotation anchor
      copy ("START POSITION" / "AFTER MOVE 3") is
      translated too.
    - **Deep link** — paste
      `/en/student/lessons/<id>` into a fresh tab. Page
      renders correctly; breadcrumb leads back to
      `/student/lessons`; status card + viewer both on
      first paint (no hydration flash because the
      walkthrough stepper is a client island but the
      prose block is server-rendered).

### #56 — Student lessons list + sidebar badge

First frontend slice of the student-side lessons surface. Wires up
`/student/lessons` as a server-rendered list page mirroring the
shape of `/student` (homework list) — `student-main__head` with
Newsreader title + two-part subtitle ("3 assigned · 1 unread"),
segmented **Unread** / **Read** tabs driven by the URL-backed
`?view=read` query param, and `.hw-cards` list below. Each card
reuses the homework list's three-column grid (46px icon tile /
body / status) but with a binary chip instead of the seven-state
homework rollup: `status === null` → **New** (neutral, never
opened), `NOT_STARTED` → **In progress** (amber, the "opened but
didn't finish" trail), `READ` → **Read** (green). Under each
non-READ card sits a **Mark as read** micro-action — a plain
`<form>` bound to the existing `finishLessonAction` server action
so the page stays a server component. The sidebar's **Lessons**
slot now carries a badge count mirroring the homework badge: the
`/student/*` layout fetches `/api/v1/me/lessons` in parallel with
the homework rollup, counts rows whose `status !== "READ"`
(explicit `NOT_STARTED` still counts as unread — it's about "stuff
waiting on you", not "stuff you've opened"), and hands it to
`<StudentSidebar>`. The sidebar also grew pathname-driven active
state so `/student/homework/<id>` highlights **Homework** and
`/student/lessons/<id>` highlights **Lessons** once the viewer
ships. Zero new CSS — the existing homework card chrome, chip
variants, and tab primitives carry the lesson surface verbatim.
No deadline / no progress bar — lessons are revisitable study
material with binary completion, so those axes would lie about
the data.

Files:

- `apps/web/src/app/[locale]/student/sidebar.tsx` — refactored to
  `"use client"`. Replaced the caller-passed `currentSection` prop
  with a `sectionFromPath(pathname)` helper that reads
  `usePathname()` (next-intl's locale-stripping variant) and
  maps the trailing portion to one of `"homework" | "lessons" |
  null`. Handles `/student` (root → homework), `/student/homework/*`
  (→ homework), and `/student/lessons/*` (→ lessons) so detail
  routes keep their section highlighted once the viewer ships.
  Added a new `lessonCount` prop rendered as a
  `.student-sidebar__count` badge on the **Lessons** link
  alongside the existing homework badge. The Lessons slot was
  previously a disabled placeholder; it's now a live `Link`
  pointing at `/student/lessons`.
- `apps/web/src/app/[locale]/student/layout.tsx` — added a second
  parallel `apiFetch` for `/api/v1/me/lessons` keyed on
  `StudentLessonRow[]` (`{id, status}`-only projection for the
  count) and computes `lessonCount` from rows where `status !==
  "READ"`. Both fetches run inside a single `Promise.all` so the
  layout still does one round-trip bundle. Silent on failure:
  the badge hides if the rollup endpoint is unreachable, matching
  the homework badge's behaviour. Passes `lessonCount` through to
  `<StudentSidebar>`.
- `apps/web/src/app/[locale]/student/actions.ts` — added
  `StudentLessonCompletionStatus = "READ" | "NOT_STARTED" | null`,
  `UpdateMyLessonCompletionResult` discriminated union, and
  `updateMyLessonCompletionAction(prev, formData)` server action
  (form-action-shaped so the lesson detail page can bind it to a
  `useActionState`-backed form). Allowed raw status values are
  `["READ", "NOT_STARTED", ""]` — empty string maps to `null` on
  the wire, contrasting with the homework twin's `["DONE",
  "SKIPPED", ""]` set. Body shape: `{status: rawStatus === "" ?
  null : rawStatus}`. Mirrors the homework action's 401 / 403 /
  404 / 409 / 400-422 / network → union mapping. Revalidates
  both `/{locale}/student/lessons` and
  `/{locale}/student/lessons/{id}` on success. Also added
  `finishLessonAction(formData)` — a void-returning wrapper
  (same shape as `finishHomeworkAction`) that reads
  `lessonId` / `locale` / `status` from the FormData, builds the
  wire body, and POSTs; errors are swallowed silently since the
  list page has no per-card error slot. The `// No admin
  override…` comment on the homework-side mirror is preserved
  here: completion is student-self-service only; there's no
  teacher endpoint to graft into the admin surface.
- `apps/web/src/app/[locale]/student/lessons/page.tsx` — new
  server component (~350 lines). Imports the existing
  `apiFetch` / `getAccessToken` / session helpers. Local
  `StudentLessonView` type mirrors the backend DTO verbatim
  (explicit ternary `status: "NOT_STARTED" | "READ" | null`).
  Auth gate + STUDENT role check with the same bounce patterns
  as the homework list. `normalizeView(raw)` coerces
  `?view=read` to the `"read"` branch, everything else to
  `"unread"`. `filterFor(view, lesson)` tests
  `status === "READ"` against the selected view. `chipFor` does
  the three-way rollup; `iconForLesson(title)` hashes the first
  Latin/digit glyph into an uppercased letter (Newsreader serif
  renders it cleanly; Unicode chess glyphs would fall back to
  colour emoji on many platforms). `formatSessionLabel(iso,
  locale, t)` formats the denormalised `sessionStartsAt` with
  `Intl.DateTimeFormat(locale, {timeZone: "Asia/Tashkent", …})`
  into a "Session · 20 Apr 18:00" pill. `MarkReadRow` renders
  a plain `<form action={finishLessonAction}>` with hidden
  inputs `lessonId` / `locale` / `status="READ"`, gated on
  `l.status !== "READ"` so the micro-action disappears once
  the student marks the row. Subtitle is composed from two
  plural-aware parts (`subAssigned` + `subUnread`), joined
  with " · ". The tabs are standard `Link`s to
  `/student/lessons` (unread) and `{pathname: "/student/
  lessons", query: {view: "read"}}` (read), carrying the
  existing `.student-tabs__tab` chrome + count badge. Empty
  states are view-aware (`empty` for unread,
  `emptyRead` for read). Network failure (not 401/403)
  surfaces a `form-error` banner above the empty state.
- `apps/web/messages/{en,ru,uz}.json` — added the new
  `student.lessonsList.*` subtree (16 keys per locale):
  `title`, `tabsLabel`, `tabUnread`, `tabRead`, `subAssigned`
  (plural), `subUnread` (plural), `blocksCount` (plural with
  `=0`/`=1`/`other`), `noBlocks`, `sessionOn` (with `{when}`
  interpolation), `chipNew`, `chipInProgress`, `chipRead`,
  `empty`, `emptyRead`, `markRead`, `loadError`. Russian uses
  full `one/few/many/other` plural forms on `subAssigned`,
  `subUnread`, and `blocksCount`; Uzbek uses `one/other`. Key
  parity verified: en=756, ru=756, uz=756 (zero drift, up from
  740 after #55).
- **No CSS changes.** The list page reuses the homework page's
  primitives verbatim: `.student-main__head`,
  `.student-tabs` + `__tab` + `__tab--on` + `__count`,
  `.hw-cards`, `.hw-card`, `.hw-card__link`, `.hw-card__icon`,
  `.hw-card__body`, `.hw-card__title`, `.hw-card__meta`,
  `.hw-card__sep`, `.hw-card__deadline` +
  `.hw-card__deadline--default` (repurposed for the session
  pill — same clock-SVG + warm-paper tint), `.hw-card__status`,
  `.hw-chip` + `.hw-chip--neutral` / `--progress` / `--ok`,
  `.hw-card__finish` + `.hw-card__finish-btn`,
  `.hw-empty` + `.hw-empty__copy`, `.form-error`.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0).

Deliberately scoped out:

- **Detail page + viewer** — `/student/lessons/[id]` routes are
  Tasks #57 (PROSE + BOARD_WALKTHROUGH) and #58 (MCQ + VIDEO).
  The sidebar's pathname-driven section highlight already
  handles `/student/lessons/<id>` so the Lessons item stays
  active once the viewer ships — no sidebar churn needed at
  that point.
- **Admin visibility into completion** — the admin side of
  lessons (Task #53) already renders the completion sheet
  inside the class detail's Lessons panel. This slice only
  touches the student surface.
- **`NOT_STARTED` "open but don't finish" instrumentation**
  — the page renders a NOT_STARTED-flagged lesson as **In
  progress** (amber chip) so the student can see they've
  touched it but not marked it read. The backend accepts
  explicit `NOT_STARTED` writes from `/me/lessons` (contrast
  with homework which 400s on that value), so a future detail
  page can stamp that trail automatically on first open
  without needing a separate endpoint.
- **Block count pill** — the row reserves a `blocksCount`
  translation key but doesn't yet render it (no meta slot on
  the card body). Will slot in once the detail page exists
  and the list starts needing a preview hint for lessons
  with zero blocks (pure-instructions posts).
- **Responsive tweaks** — the ≤640px card stack rules inherited
  from the homework list already handle the lesson card layout
  without additional breakpoints. Verified in DevTools: iPhone
  SE (375×667) renders without overflow.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Seed prep — sign in as admin (`admin@demo.local` /
   `admin12345`). Open a class that has `student@demo.local`
   enrolled (enrol via the roster panel if not); if the class
   has no student yet, the demo seeder provides the login. Go
   to the class's Lessons panel (`/en/admin/classes/<id>`) and
   create three lessons with varied shapes:
    1. A lesson with no session anchor ("Lesson 1: Opening
       Principles") — the row's session pill should disappear.
    2. A lesson anchored to an upcoming session ("Lesson 2:
       The Italian Game") — so the "Session · &lt;date&gt;" pill
       renders.
    3. A lesson with at least one authored block (either kind
       — PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO) so the
       backend's `blockCount` is non-zero; even though the list
       doesn't yet render the block count, it'll become
       visible once the detail page ships.
3. Log out, sign in as `student@demo.local` / `student12345`.
   Land on `/en/student` (homework list).
4. Verify the sidebar:
    1. The **Lessons** row under **LEARN** is now a live link
       (not a muted placeholder) with a count badge reading
       **3** on the right — all three seeded lessons are
       unread.
    2. Click **Lessons**. URL becomes `/en/student/lessons`;
       the sidebar's Lessons row picks up the `--on` variant
       (accent-soft background + accent-ink text); Homework
       drops the highlight.
5. Happy path — list chrome:
    1. Page title reads **Lessons** in Newsreader serif.
       Subtitle reads **3 assigned · 3 unread** (plural-aware;
       drops the "nothing" branch when counts are > 0).
    2. Two-tab segmented toggle on the right: **Unread 3** (on)
       + **Read** (no count, since nothing's read yet).
    3. Three cards stacked. Each shows a warm-paper icon tile
       with an uppercase Latin glyph ("L") on the left, the
       Newsreader title, the class name as the meta strip's
       last item, and (on the row anchored to a session) a
       **Session · 20 Apr 18:00** pill mid-strip. On the
       right: a neutral **New** chip (the stock state when
       `status === null`).
    4. Under each card, a **Mark as read** micro-action sits
       in its own form-action row — same chrome as the
       homework list's finish button.
6. Happy path — mark a lesson read:
    1. Click **Mark as read** on any row. Page revalidates,
       the row disappears from the Unread tab, the Unread tab
       count drops to **2**, and the Read tab now shows
       **Read · 1**.
    2. Sidebar badge drops from **3** to **2**.
    3. Click the **Read** tab. URL becomes
       `/en/student/lessons?view=read`. The row you just
       marked appears with a green **Read** chip and no
       Mark-as-read button.
    4. Click **Unread** — URL sheds the query param; the
       remaining two rows return.
7. Edge cases worth eyeballing:
    - **NOT_STARTED "in progress" chip** — this slice doesn't
      yet write NOT_STARTED (that's the detail page's job in
      Task #57). To exercise the amber chip now, POST to the
      completion endpoint directly:
      ```bash
      STU=$(curl -s -X POST \
        http://localhost:8080/api/v1/auth/login \
        -H 'content-type: application/json' \
        -d '{"email":"student@demo.local","password":"student12345"}' \
        | jq -r .accessToken)
      curl -s -X PUT \
        http://localhost:8080/api/v1/me/lessons/$LESSON_ID/completion \
        -H "authorization: Bearer $STU" \
        -H 'content-type: application/json' \
        -d '{"status":"NOT_STARTED"}'
      ```
      Refresh `/en/student/lessons` — that row now shows the
      amber **In progress** chip. It still sits under Unread
      (the filter is `status === "READ"` for the Read tab).
      The **Mark as read** micro-action is still present so
      the student can close the loop.
    - **Empty state, unread** — on a class with no assigned
      lessons, the Unread tab shows "No lessons assigned yet.
      Check back after your next class." in the dashed-border
      empty card; Read tab shows "You haven't finished any
      lessons yet. Mark one as read when you're through."
    - **Empty state, read** — a student with lessons but none
      marked read sees the Read tab's empty copy even though
      the Unread list is populated.
    - **Load failure** — kill the backend (`ctrl-C`) and
      refresh `/en/student/lessons`. The form-error banner
      renders above the empty card; the sidebar badge hides
      (layout-level fetch also failed silently).
    - **Unassigned student** — a student with no class
      memberships lands on the page with zero rows; subtitle
      reads "nothing assigned · all caught up"; the sidebar
      badge is hidden (count of 0).
    - **Responsive ≤ 820px** — DevTools → device toolbar →
      **iPad Mini** (768×1024). The sidebar collapses to a
      horizontal top strip (brand + nav chips); the main
      column takes the full width; cards stay in the three-
      column grid.
    - **Responsive ≤ 640px** — switch to **iPhone 12 Pro**
      (390×844). Card status column stacks under the body;
      the chip spans the row width. Mark-as-read button
      stretches to full width.
    - **Dark mode** — toggle theme. Card icon tiles flip to
      a darker paper tint; the three chip variants
      (`--neutral` / `--progress` / `--ok`) keep their
      accent tints legible against the darker paper.
    - **`/ru/student/lessons`** / **`/uz/student/lessons`** —
      all 16 new keys pick up their translations. Russian
      uses `one/few/many/other` on `subAssigned` / `subUnread`
      (e.g. "1 урок / 2 урока / 5 уроков"); Uzbek uses
      `one/other` ("1 ta tayinlangan" / "5 ta tayinlangan").
      Session pill shows the localised short month
      abbreviation (`20 апр · 18:00` / `20 apr · 18:00`).

### #55 — Frontend author: Lesson blocks editor (MCQ + VIDEO)

Second of two frontend block-editor slices, completing the four v1
lesson-block kinds on both the **New lesson** and **Edit lesson**
forms. Task #54 already structured `LessonBlocksEditor` as a generic
kind-switcher list, so this slice slots two new `case` arms into the
per-kind render switch + two new CTAs in the card footer
(**Add quiz question** / **Add video**) without any architectural
churn. **MCQ** — `question` textarea (≤500 chars, char counter with
over-limit tint at 501+), a dashed-border options fieldset with a
radio column for `correctIndex` (single-choice answer key), 2–4
text inputs (add / remove below the min/max disables the respective
button), blur-dirty duplicate detection (case-insensitive, trimmed
comparison matching the backend validator's exact rule), and an
optional `explanation` textarea (≤2,000 chars, char counter). When
an option is removed, `correctIndex` shifts to preserve the teacher's
answer-key intent: removing the currently-correct option resets
`correctIndex` to `0`; removing an option before the correct one
decrements it; removing one after it is a no-op. **VIDEO** — single
`url` input plus a live provider preview pill (`youtube` / `vimeo`)
derived client-side from the host so the author sees immediately
which provider the server will stamp, plus a five-bin diagnostic
(`ok` / `empty` / `invalid` / `not-https` / `host-not-allowed`) that
paints an inline error after first blur. The allowlist of 6 hosts is
mirrored client-side (`youtube.com`, `www.youtube.com`, `youtu.be`,
`vimeo.com`, `www.vimeo.com`, `player.vimeo.com`); any mismatch
surfaces the full list in a dashed-border info paragraph under the
input so the author can copy a compliant URL without guessing. The
existing three-state PATCH semantics + the
`lessonResourcesFromBlocks` serialiser from #54 handle both new
kinds with zero changes: VIDEO blocks emit `{url}` only — the
`provider` field is server-derived from the trusted host on save,
matching the backend's deliberate "don't trust client-claimed
providers" stance from Task #50.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-blocks-editor.tsx`
  — heavy extension (~700 new lines bringing the file to ~2,100).
  `LessonBlock` union grew two new arms: `LessonMcqBlock`
  (`{kind: "MCQ", question, options, correctIndex, explanation?}`)
  and `LessonVideoBlock` (`{kind: "VIDEO", url}` — no `provider`
  client-side; the preview is derived, not stored on the block). New
  constants mirror the backend caps:
  `MCQ_QUESTION_MAX = 500`, `MCQ_OPTION_MAX = 200`,
  `MCQ_EXPLANATION_MAX = 2000`, `MCQ_MIN_OPTIONS = 2`,
  `MCQ_MAX_OPTIONS = 4`, `VIDEO_ALLOWED_HOSTS = [...]`. New pure
  helpers: `createEmptyMcqBlock()` (seeds two empty options +
  `correctIndex: 0`), `createEmptyVideoBlock()`,
  `firstDuplicateOptionIndex(options)` (case-insensitive, trimmed —
  returns the index of the first repeat or `-1`),
  `diagnoseVideoUrl(url)` (returns a discriminated union so the
  caller can gate blur-dirty rendering on the `kind`), and
  `deriveVideoProvider(url)` (`"youtube" | "vimeo" | null`). Two new
  subcomponents: `McqBlockCard` (~180 lines) and `VideoBlockCard`
  (~115 lines), each wired with `dirty` state + `onBlur` setters so
  errors only render after the field has been touched. MCQ option
  removal runs through `removeOptionAt(idx)` which shifts the
  correctIndex per the rule above. `lessonBlocksFromResources` gained
  two new `case` arms: MCQ hydrates from the backend payload with
  `options` padded to `MIN_OPTIONS` and sliced to `MAX_OPTIONS` so a
  pathological stored payload can't explode the UI; VIDEO hydrates
  only the `url` (discards any stored `provider` — the server will
  re-derive on save). `lessonResourcesFromBlocks` gained matching
  arms that emit only the canonical keys: MCQ drops `explanation` if
  blank after trim; VIDEO emits `{url}` only (no `provider`).
- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-panels.tsx`
  — extended `lessonBlocksEditorLabels(t)` with ~35 new key mappings
  covering `addMcq`, `addVideo`, `blockKindMcq`, `blockKindVideo`,
  and the full `mcq*` + `video*` leaf trees. No other changes — the
  kind-switcher in `LessonBlocksEditor` owns the per-arm render so
  the form shells (`NewLessonForm` / `EditLessonForm`) didn't need
  to learn about the new kinds.
- `apps/web/messages/{en,ru,uz}.json` — added 35 keys per locale
  (740 total each, up from 705 after #54) under the existing
  `admin.classes.lessons.blocks.*` subtree. Coverage:
  list chrome (addMcq, addVideo, blockKindMcq, blockKindVideo);
  MCQ (mcqQuestionLabel, mcqQuestionPlaceholder,
  `mcqQuestionCharCount` with `{count}/{max}`, mcqQuestionTooLong,
  mcqOptionsLegend, mcqOptionsHint, `mcqOptionLabel` with `{index}`,
  mcqOptionPlaceholder, mcqOptionCorrect, mcqOptionRemove,
  mcqOptionDuplicate, mcqOptionEmpty, mcqAddOption,
  `mcqOptionsMinReached` with `{min}`,
  `mcqOptionsMaxReached` with `{max}`, mcqExplanationLabel,
  mcqExplanationPlaceholder, `mcqExplanationCharCount` with
  `{count}/{max}`, mcqExplanationTooLong);
  VIDEO (videoUrlLabel, videoUrlPlaceholder, videoInvalidUrl,
  videoNotHttps, videoHostNotAllowed, videoAllowedHosts,
  videoEmpty, videoProviderLabel, videoProviderYoutube,
  videoProviderVimeo, videoProviderUnknown, videoHint). Key parity
  verified: en=740, ru=740, uz=740 (zero drift). Russian uses
  full `one/few/many/other` plurals on the three char-count keys;
  Uzbek uses `one/other`.
- `apps/web/src/app/globals.css` — appended ~336 lines after the
  Task #54 dark-mode polish block under a new header "Lesson
  blocks editor — MCQ + VIDEO additions (Task #55)". Kind-tag
  variants: `.lesson-blocks__kind-tag--mcq` picks up a warm amber
  tint (`oklch(0.42 0.14 75)` on the base paper-sunken fill) so
  the MCQ pills scan apart from the default PROSE/WALKTHROUGH
  neutral; `.lesson-blocks__kind-tag--video` picks up a cool
  indigo tint (`oklch(0.42 0.14 265)`) so VIDEO pills scan apart
  from MCQ. Dark-mode overrides on both so the tints stay legible
  against the darker paper. MCQ primitives:
  `.lesson-mcq` (column flex, 12px gap), `.lesson-mcq__question-field`
  + `.lesson-mcq__question` (88px min-height textarea),
  `.lesson-mcq__meta` (flex-wrap space-between for
  charcount + too-long label), `.lesson-mcq__options` (dashed-border
  fieldset on the sunken surface so the answer-key block reads as
  nested against the rest of the card), `.lesson-mcq__options-legend`
  (11px uppercase), `.lesson-mcq__options-hint`,
  `.lesson-mcq__options-list` (list reset),
  `.lesson-mcq__option-row` (3-col grid `auto 1fr auto` laying out
  the radio column / input / remove button), `.lesson-mcq__correct-radio`
  + `.lesson-mcq__correct-text` (screen-reader-only label so the
  radio's semantic owner reads "Option 2 — correct" without
  clutter), `.lesson-mcq__option-field` + `.lesson-mcq__option-label`
  (11px uppercase above each input), `.lesson-mcq__option-input`
  + `--error` modifier (red border via `--danger`),
  `.lesson-mcq__option-remove` (ghost button with subtle hover),
  `.lesson-mcq__option-error` (grid-column: 1 / -1 so the error
  wraps under the full row), `.lesson-mcq__options-footer`
  (flex-wrap row carrying the Add option button + min/max note),
  `.lesson-mcq__options-note` (muted help text),
  `.lesson-mcq__explanation-field` + `.lesson-mcq__explanation`
  (76px min-height textarea). VIDEO primitives: `.lesson-video`
  (column flex), `.lesson-video__field`, `.lesson-video__input`
  + `--error` modifier, `.lesson-video__meta` (flex-wrap for
  provider pill + hint), `.lesson-video__provider` (small rounded
  pill tinted by provider — mirrors the kind-tag colour scheme so
  a YouTube-derived preview reads warm-amber-adjacent, a
  Vimeo-derived preview reads cool-indigo-adjacent),
  `.lesson-video__hint` (12px muted), `.lesson-video__allowed`
  (dashed-border paragraph listing the host allowlist that
  unfurls below the input on host-mismatch errors),
  `.lesson-video__allowed-list` (inline mono code per host).
  Responsive `@media (max-width: 640px)` rule collapses the MCQ
  option row's 3-col grid to a single column so the remove button
  doesn't float disconnected when the input shrinks, and stacks
  the VIDEO meta row (provider pill / hint) vertically. Also
  fixed a pre-existing naming bug from Task #54: the PROSE char
  counter's TSX uses `lesson-prose__charcount` (no hyphen) but
  Task #54 CSS defined `.lesson-prose__char-count--over` with
  a hyphen, so the over-limit red tint wasn't painting —
  added the correct `.lesson-prose__charcount` +
  `.lesson-prose__charcount--over` selectors (kept the old
  hyphenated rule as vestigial so nothing else breaks). CSS
  brace balance verified: 826 open / 826 close.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).

Deliberately scoped out:

- **Student viewer** — Tasks #57 (PROSE + BOARD_WALKTHROUGH) and
  #58 (MCQ + VIDEO). The block payloads are already shipping
  unscrubbed (lessons never had a `solution` field to strip, so
  MCQ's `correctIndex` + `explanation` arrive client-side
  verbatim — the viewer mediates reveal UX, not the backend).
- **Per-option rich text / board shapes** — MCQ options are
  plain strings (≤200 chars). No bold / italic / embedded
  diagrams in v1; if curriculum authors ask for it, a future
  slice can layer markdown onto them reusing the PROSE
  rehype pipeline.
- **Video playlists / start-at-timestamp** — VIDEO blocks store
  a single URL. If we ever want to auto-chain a playlist or jump
  to a specific timestamp, that's a schema change
  (`startSeconds?: number`, `endSeconds?: number`,
  `nextBlockOnFinish?: boolean`) + a validator change + a
  viewer change. Explicitly out of v1.
- **Drag-to-reorder options within an MCQ** — current UX
  requires removing + re-adding options to reorder. Single
  option reordering has never come up as a request; file a
  follow-up only if authoring feedback asks for it.
- **Per-lesson author-side preview** — clicking a block in
  the editor doesn't render a student-view preview. Once the
  student viewer lands (#57/#58), the author can just visit
  the lesson route as their own dev student to preview. A
  "Preview as student" button inside the editor is a polish
  slice for later.
- **Video URL normalisation** — we don't rewrite
  `youtube.com/watch?v=X` into an embed-friendly
  `youtube.com/embed/X`. That's a viewer concern (Task #58)
  because the rewrite shape depends on whether we use
  iframe-based embeds, the `lite-youtube-embed` custom element,
  or a bespoke privacy-respecting player. Storing the canonical
  user-facing URL here keeps the editor honest.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as a school admin (`admin@demo.local` / `admin12345`)
   or a teacher assigned to a class. Open the class detail
   page (`/en/admin/classes/<classId>`) and scroll to the
   **Lessons** card.
3. Happy path — create a lesson with all four block kinds:
    1. Click **Add lesson**. Title: "Lesson 2: Tactics
       Checkpoint". Leave instructions + session empty.
    2. Click **Add study text** (from Task #54) — PROSE card
       appears.
    3. Click **Add board walkthrough** (Task #54) — a board
       card appears below PROSE.
    4. Click **Add quiz question**. A new MCQ card appears
       below, with its kind tag reading **Quiz question** on
       an amber-tinted pill. Fill the **Question** textarea
       ("What's the best reply to 1.e4 for a counter-attack
       player?"). Inside the **Options** fieldset, fill the
       first two inputs with "1…c5 (Sicilian)" and "1…e5
       (Open game)". Click **Add option** — a third empty
       input appears with a radio. Type "1…e6 (French)".
       Click **Add option** once more → "1…c6 (Caro-Kann)".
       The **Add option** button now disables (max 4
       reached) and the footer note reads "Max 4 options."
       Tick the radio next to the Sicilian option so
       `correctIndex = 0`.
    5. Optionally fill **Explanation** ("The Sicilian is the
       sharpest response at the master level.").
    6. Click **Add video**. A new VIDEO card appears with
       its kind tag reading **Video** on an indigo-tinted
       pill. Paste a YouTube URL
       (`https://www.youtube.com/watch?v=abc123`). The meta
       row immediately shows **Provider: YouTube** in a
       warm-amber-adjacent pill and no error.
    7. Submit the form.
    8. The form closes. The new lesson row appears at the
       top of the list with `4 blocks` in the meta strip.
4. Happy path — edit + round-trip:
    1. Click **Edit** on the row. "Loading chess content…"
       line renders briefly, then all four blocks hydrate
       with their saved content. The PROSE char counter
       matches the body; the BOARD_WALKTHROUGH board is at
       the post-move position; the MCQ's question + 4
       options + explanation are all back, with the radio
       pre-selected on the originally-correct option; the
       VIDEO URL is back with the provider pill correctly
       showing **YouTube**.
    2. On the MCQ card, click **Remove** on the third option
       (French). The option disappears, the radio count
       drops to 3. Because `correctIndex` was `0`
       (Sicilian), it's unaffected.
    3. On the VIDEO card, replace the URL with a Vimeo URL
       (`https://vimeo.com/12345`). The provider pill
       re-computes to **Vimeo** (cool-indigo-adjacent tint)
       with no error.
    4. Click **Save changes**. The row re-renders with
       `4 blocks` still (count unchanged; just internal
       content shifted).
5. Edge cases worth eyeballing:
    - **MCQ duplicate options**: on a fresh MCQ, fill two
      options with "Knight" and "KNIGHT" (case-insensitive
      match). Blur the second input. An inline red error
      appears under the second input ("This option matches
      another one.") and the input picks up a red border
      (via `.lesson-mcq__option-input--error`). Submit goes
      through anyway (no client gate) but the backend returns
      the `error.invalid` copy under the submit button. Change
      the second option to "Bishop" — the error clears.
    - **MCQ over-limit question**: paste 550 chars of Lorem
      Ipsum into the question textarea. The char counter
      reads "550 / 500" in a danger-tinted colour
      (`.lesson-mcq__meta` + `.lesson-mcq__question-field`
      over-limit state) and a "Too long — trim to fit" hint
      appears. Trim to 500 — tint clears.
    - **MCQ min/max gating**: on a fresh MCQ with 2 options,
      try clicking a **Remove** button — nothing happens
      (the buttons disable below the min of 2 and the footer
      reads "Min 2 options."). Add two more options to
      reach 4. **Add option** disables; footer reads "Max 4
      options."
    - **MCQ correctIndex shifting**: with a 4-option MCQ and
      the radio on option 2 (`correctIndex = 1`), click
      **Remove** on option 1. The radio correctly stays on
      what's now the first-remaining option (`correctIndex`
      decrements from 1 to 0 — the intent is preserved).
      Now set `correctIndex` to the last option. Click
      **Remove** on that same option. `correctIndex` resets
      to 0 because the teacher's answer-key target just
      vanished — the card re-renders with the radio on
      option 1.
    - **VIDEO http scheme**: paste
      `http://www.youtube.com/watch?v=abc123`. Blur. An
      inline red error appears ("Use an https:// URL.")
      and the input picks up a red border. The provider
      pill reads **Unknown** in a muted-neutral tint.
    - **VIDEO host not allowed**: paste
      `https://dailymotion.com/video/abc`. Blur. An inline
      error appears ("This host isn't on the allowed
      list.") plus a dashed-border paragraph below the
      input lists the 6 allowed hosts as inline mono code
      for copy-paste. Fix by switching to a
      `https://vimeo.com/...` URL — the error clears and
      the provider pill reads **Vimeo**.
    - **VIDEO empty on submit**: leave the URL blank and
      try to submit. Browser-native `required` catches it
      first (the input has the HTML `required` attribute);
      if you force past it via devtools, the backend
      returns `error.invalid`.
    - **Unassigned teacher**: sign in as a teacher who
      isn't on the class's teacher roster. The Lessons
      panel renders the existing rows but the **Add
      lesson** and per-row **Edit** buttons are hidden;
      the block editor is never reachable from the UI. The
      backend still enforces a crafted PATCH with 403.
    - **Responsive ≤ 640px** — DevTools → device toolbar →
      **iPhone SE** (375×667). The MCQ option row's 3-col
      grid collapses to a single column (radio + label on
      one line, input on the next, remove button on the
      third stretched to full width). The VIDEO meta row
      (provider pill / hint) stacks vertically so the
      pill isn't squeezed against the hint.
    - **Dark mode** — toggle theme. MCQ amber kind-tag tint
      deepens (via `color-mix` fallback against the dark
      paper surface); VIDEO indigo kind-tag tint deepens
      similarly; dashed-border "Allowed hosts" paragraph
      darkens one step; all option-input and url-input
      `--error` variants keep their red border legible
      against the dark paper.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — all 35 new MCQ + VIDEO keys pick up their
      translations. The char counters render Russian's
      `one/few/many/other` forms on the three plural keys
      (e.g. "501 символ / 502 символа / 505 символов") and
      Uzbek's `one/other`. The "Max 4 options." /
      "Min 2 options." footer notes interpolate `{max}` /
      `{min}` cleanly. The provider pill's three states
      (YouTube / Vimeo / Unknown) are all translated.

### #54 — Frontend author: Lesson blocks editor (PROSE + BOARD_WALKTHROUGH)

First of two frontend block-editor slices on top of the Task #53
lessons scaffold. Lights up two of the four backend-supported block
kinds on both the **New lesson** and **Edit lesson** forms inside the
class detail page's Lessons panel: **PROSE** (single markdown textarea
with a live char counter against the 50,000-char backend cap) and
**BOARD_WALKTHROUGH** (a chess board editor mirroring the homework
BOARD block's Setup / Add moves tabbed UX — drag-and-drop palette for
starting position, PGN / UCI move recorder, orientation toggle — with
the hidden solution section torn out and replaced by a ply-anchored
annotations list). The block list sits below the session picker on
both forms; each block is a collapsible card with a kind tag in the
header and a **Remove block** button. The footer carries two CTAs
(**Add study text** / **Add board walkthrough**); MCQ + VIDEO will
extend the same list + kind-switcher in Task #55 without a fresh
component. Edit form uses the same lazy-fetch-on-first-render +
`blocksTouched` gate the homework twin uses, so the existing
three-state PATCH on `resources` still works: untouched editor → omit
the hidden input entirely (leaves existing blocks alone); emptied
editor → send `"[]"` (clears all blocks); populated → send the full
serialised list. The existing `parseResources` + `toBackendResource`
helpers in `lesson-actions.ts` (landed in #53 with that explicit
future-block-editor affordance) needed zero changes — the editor just
slots in as the block-list owner.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-blocks-editor.tsx`
  — new `"use client"` component (~1,410 lines, heavy refactor of the
  existing `board-blocks-editor.tsx` homework twin). Exports
  `LessonBlocksEditor`, `lessonBlocksFromResources`,
  `lessonResourcesFromBlocks`, `LessonBlock` (discriminated union on
  `kind`), `LessonProseBlock`, `LessonWalkthroughBlock`,
  `LessonAnnotation`, `LessonBlocksEditorLabels`, and
  `STARTING_FEN`. The chess helpers (`parseFenForSetup`,
  `composeFen`, `sanitizeCastling`, `play`, `fenAfter`, `isValidFen`,
  the `SetupPanel` subcomponent, all the palette drag-and-drop logic,
  the PGN paste-parse path) are byte-identical copies of the homework
  editor's — the file header explicitly flags this with a note that
  once BOARD_WALKTHROUGH has stabilised alongside homework BOARD a
  future refactor should extract the chess helpers to a shared
  module. PROSE card renders a single `<textarea>` with a meta row
  underneath carrying the char count (e.g. "1,234 / 50,000") that
  flips to a danger tint via `.lesson-prose__char-count--over` when
  it exceeds the cap (the editor doesn't hard-block submit since the
  backend re-validates; it surfaces the overflow so the author can
  trim). BOARD_WALKTHROUGH card reuses the homework Setup / Add
  moves tabbed layout verbatim minus the solution disclosure, and
  appends a new **Annotations** section after the Add moves panel
  with a 3-col grid `110px 1fr auto` per row (ply number input /
  text textarea / delete button). Authors can add up to 64
  annotations with `ply ∈ [0, moves.length]` inclusive — the editor
  clamps out-of-range input back into the valid window before
  emitting so the backend validator never 400s on a bound miss from
  this editor. An "Add at start" affordance drops an annotation at
  `ply=0` so the author can anchor a note to the starting position
  without having to first play a move.
- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-panels.tsx` —
  wired `LessonBlocksEditor` into both **NewLessonForm** and
  **EditLessonForm**. Added `lessonBlocksEditorLabels(t)` helper
  (~60 key mappings, same shape as `boardEditorLabels` in the
  homework panel) that pulls the full `admin.classes.lessons.blocks.*`
  subtree out of next-intl for handoff to the editor. NewLessonForm
  owns a `blocks: LessonBlock[]` state; only emits the hidden
  `resources` input when `blocks.length > 0`, resets the array to
  `[]` on successful submit via the existing
  `createWasSubmitted`/`queueMicrotask(onCreated)` close-on-success
  ref. EditLessonForm adds four new state slots (`blocks`,
  `blocksTouched`, `detailLoading`, `detailLoaded`), runs
  `readLessonDetailAction(classId, lesson.id)` on first render
  (state-gate — no useEffect), hydrates existing blocks from the
  returned `detail.resources` via `lessonBlocksFromResources`, and
  emits the hidden `resources` input only when `blocksTouched` is
  true. Submit button is disabled while `detailLoading` so a fast
  author can't accidentally save a blank-blocks payload that would
  clear the backend's blocks before they loaded. A 3-way
  conditional renders the editor slot (loading line / error banner
  / editor).
- `apps/web/messages/{en,ru,uz}.json` — removed the two Phase-53
  placeholder keys `admin.classes.lessons.form.blocksHint` +
  `form.blocksEditHint` (the editor replaces what they were
  describing) and added the full `admin.classes.lessons.blocks.*`
  subtree (62 keys per locale) under a freshly-grouped block
  after `admin.classes.lessons.form.*`. Coverage: list chrome
  (heading, hint, loading, noBlocks, addProse, addWalkthrough,
  removeBlock, blockKindProse, blockKindWalkthrough); PROSE
  (proseLabel, prosePlaceholder, `proseCharCount` with
  `{count}/{max}` interpolation, proseTooLong); BOARD_WALKTHROUGH
  metadata (blockTitleLabel, blockTitlePlaceholder,
  startingFenLabel, resetToStart, useCurrentAsStart,
  orientationLabel + orientationWhite/Black); move-recording
  (movesHeading, `movesCount` plural, movesEmpty, pastePgnLabel,
  pastePgnPlaceholder, pastePgnReplace, pastePgnAppend,
  undoLastMove, clearAllMoves, sideToMove +
  sideWhite/Black, invalidFen, invalidPgn); mode tabs (modeSetup,
  modeMoves); setup-mode (setupFillStart, setupEmpty,
  setupPaletteHint, setupRemoveHint, setupCastling,
  setupCastleK/Q/k/q, setupSideToMove, setupAdvancedShow/Hide,
  setupIllegalPositionNotice); annotations (annotationsHeading,
  annotationsHint, annotationsEmpty, `annotationsCount` plural,
  annotationsAdd, annotationsAddAtStart, annotationsDelete,
  annotationsPlyLabel, `annotationsPlyHint` with `{max}`,
  annotationsTextLabel, annotationsTextPlaceholder,
  `annotationsMaxReached` with `{max}`). Russian uses full
  `one/few/many/other` plurals on `movesCount` +
  `annotationsCount`; Uzbek uses `one/other`. Key parity verified:
  en=705, ru=705, uz=705, zero drift.
- `apps/web/src/app/globals.css` — appended ~175 lines under a new
  header "Lesson blocks editor (Task #54 — PROSE +
  BOARD_WALKTHROUGH)". Outer scaffolding (the block list card, the
  per-block card frame, the move recorder, the setup palettes)
  reuses the existing `.hw-boards*` / `.hw-board-card*` /
  `.sched-field*` primitives from the homework editor; only the
  lesson-specific additions live in the new block. Key primitives
  added: `.lesson-blocks__footer` (overrides the column-flex
  parent to a row-wrap for the Add-prose / Add-walkthrough CTAs),
  `.lesson-blocks__kind-tag` (11px uppercased pill in the per-
  block card header identifying PROSE vs WALKTHROUGH — different
  accent fill per kind so the author can scan the list at a
  glance), `.lesson-prose__field` + `.lesson-prose__textarea`
  (min-height 200px, vertical resize, focus-visible accent ring),
  `.lesson-prose__meta` (flex-wrap row for the char count +
  overflow-state label), `.lesson-prose__char-count--over` (flips
  the count text to the danger tint when length > 50,000),
  `.lesson-walkthrough__annotations` (section with a dashed
  top-border so the annotations list reads as a distinct
  sub-section inside the board card, mirroring the homework
  editor's hidden-solution panel treatment),
  `.lesson-walkthrough__annotations-head` (flex row with title +
  add CTA + count), `.lesson-walkthrough__annotations-list` (list
  reset), `.lesson-walkthrough__annotation-row` (3-col grid
  `110px 1fr auto` laying out ply input / text textarea / remove
  button), `.lesson-walkthrough__ply` + `.lesson-walkthrough__text`
  (sized inputs), `.lesson-walkthrough__annotation-remove` (ghost
  button with subtle hover state). Responsive
  `@media (max-width: 640px)` override stacks the footer CTAs and
  the annotation row's three columns into a single column so
  narrow viewports don't chew the annotation text area. Dark-mode
  override on the annotation row's background so the sub-section
  still reads as nested against the darker paper surface. CSS
  brace balance verified: 770 open / 770 close.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).

Deliberately scoped out:

- **MCQ + VIDEO kinds** — Task #55. The `LessonBlock` union + the
  list's kind-switcher footer are already structured to accept two
  more arms without refactoring; #55 just adds two more
  `LessonXxxBlock` types + two more `case` arms in the per-kind
  render switch + two more CTAs in the footer.
- **Student viewer** — Tasks #57 (PROSE + BOARD_WALKTHROUGH) and
  #58 (MCQ + VIDEO). The server-side block payload is already
  shipping unscrubbed (#52: lessons don't strip `solution` on the
  wire because there's no such thing), so the viewer will be a
  straight render pass over the authored JSON.
- **Markdown preview tab on the PROSE editor** — the editor only
  shows the raw textarea + a char counter. The viewer will render
  the markdown properly (rehype + sanitize pass) in Task #57; for
  authoring it's faster to eyeball the raw text + sanity-check by
  previewing in-place on the published lesson. Can revisit if
  authoring feedback asks for it.
- **Branching variations on BOARD_WALKTHROUGH moves** — the
  backend payload stores a single linear `moves[]`. If we ever
  want "1.e4 {alt: 1.d4} e5 …" branching, that's a schema change
  (`variations[]` on the block), a validator change, AND a
  chess-heavy editor rewrite. Explicitly out of v1.
- **Drag-to-reorder blocks** — the backend persists `position`
  from list index so reordering the in-memory array would just
  work on save, but the UI doesn't yet expose a reorder handle.
  Authors can delete + re-add to move things around. File as a
  follow-up if it proves painful.
- **Per-annotation rich-text** — annotations are plain strings
  (≤200 chars). No bold / italic / board shapes in v1. A future
  slice can layer markdown onto them reusing the PROSE rehype
  pipeline.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as a school admin (`admin@demo.local` / `admin12345`)
   or a teacher assigned to a class with at least one enrolled
   student. Open the class detail page
   (`/en/admin/classes/<classId>`) and scroll to the **Lessons**
   card.
3. Happy path — create with both block kinds:
    1. Click **Add lesson**. Title: "Lesson 1: The Italian
       Game". Leave instructions + session empty.
    2. Scroll to the new **Chess content** / lesson blocks
       section under the session picker. Click **Add study
       text**. A PROSE card appears with its kind tag reading
       **Study text** and an empty markdown textarea below.
       Type a few paragraphs (headings with `#`, lists with
       `-`, code fences all welcome). The char counter below
       reads e.g. "234 / 50,000" in muted ink.
    3. Click **Add board walkthrough**. A BOARD_WALKTHROUGH
       card appears below with the Setup / Add moves tabbed
       layout + orientation radio. On the Setup tab, click
       **Fill starting position**. Flip to the **Add moves**
       tab, drag `e2→e4`, `e7→e5`, `g1→f3` on the board —
       each move appears in the moves chip list as `e2e4`
       etc.
    4. Scroll down inside the walkthrough card. Under the
       dashed top-border, a new **Annotations** section reads
       "No annotations yet." Click **Add annotation**. A row
       appears with a ply input prefilled to the current move
       count (`3`) and an empty text textarea. Type "After
       3.Nf3 — developing the kingside knight."
    5. Click **Add annotation at start**. A second row appears
       with `ply=0`. Type "Starting position of the Italian
       Game."
    6. Submit the form.
    7. The form closes. The new lesson row appears at the top
       of the list with `2 blocks` in the meta strip.
4. Happy path — edit + round-trip:
    1. Click **Edit** on the row. The form expands. A brief
       "Loading chess content…" line renders in the blocks
       slot (≤300 ms on localhost).
    2. Once loaded, both blocks hydrate with their original
       content. The PROSE markdown is in the textarea (char
       counter matches). The walkthrough board is at the
       post-move-3 position; the annotations list shows both
       rows in ply order (0 first, 3 second).
    3. Drag a move for black (`d7→d6` or similar). The moves
       list grows. Click **Add annotation**, type a note
       anchored to ply 4.
    4. Click **Save changes**. The row re-renders with
       `2 blocks` still (the editor sent the full `resources`
       replace list; the count is unchanged because we
       still have two blocks, just one of them has more moves
       + annotations now).
5. Happy path — three-state PATCH preserved:
    1. Click **Edit** on the row again. **Do not touch the
       blocks editor.** Change the **Title** to "Lesson 1
       (revised)" and click **Save changes**.
    2. Re-open **Edit**. The blocks should still hydrate with
       the full content from step 4.3 — unchanged. Proves
       the "untouched editor → leave blocks alone" path.
    3. Now click **Remove block** on both cards one at a time
       (both disappear — the editor is now empty, "No
       content blocks yet."). Save.
    4. Re-open **Edit**. The blocks area should read "No
       content blocks yet." and the lesson row meta should
       say `0 blocks`. Proves the "emptied editor → clear
       all blocks" path.
6. Edge cases worth eyeballing:
    - **PROSE overflow**: paste ~52,000 chars of Lorem
      Ipsum into the study-text textarea. The char counter
      flips to a danger tint ("52,000 / 50,000") and an
      inline "Too long — trim to fit" hint appears below.
      Submit goes through anyway (no client gate) but the
      backend returns the `error.invalid` copy under the
      submit button. Trim the text to fit and resubmit —
      success.
    - **Invalid FEN via the Advanced disclosure**: on a
      BOARD_WALKTHROUGH's Setup tab, expand **Advanced: edit
      FEN directly** and paste `not-a-fen`. The inline "This
      is not a valid position." notice appears and the board
      doesn't update. Moves tab shows an orange warning banner
      telling you the position isn't legal; drag handles are
      disabled on the board until you fix it.
    - **Annotation ply clamp**: with a walkthrough that has
      3 moves, try typing `99` into a new annotation's ply
      input. Blur — the value clamps back to `3` (the
      inclusive upper bound). Try `-5` — clamps to `0`.
      These are editor-side only so the backend's 400 path is
      never hit from this editor; the backend still enforces
      the bound for crafted POSTs.
    - **Max annotations**: click **Add annotation** 64 times.
      The button disables and the meta line reads "Max
      annotations reached (64)."
    - **Mid-walkthrough checkbox castling clamp**: same as
      the homework BOARD block — drag the white king off
      e1 and the two white castling checkboxes auto-uncheck
      and grey out. Move it back to e1 and the checkboxes
      re-enable (but stay unchecked — returning the king
      doesn't restore the right).
    - **Unassigned teacher**: sign in as a teacher who isn't
      on the class's teacher roster. The Lessons panel
      renders the existing rows but the **Add lesson** and
      per-row **Edit** buttons are hidden; the block editor
      is never reachable from the UI. The backend still
      enforces a crafted PATCH with 403.
    - **Detail fetch failure on Edit**: kill the backend
      (`ctrl-C`) while the form is expanded. Click **Edit**
      on another row — the blocks slot shows the
      `error.network` copy instead of the editor. Restart
      the backend + collapse / re-expand the row to retry.
    - **Responsive ≤ 640px** — DevTools → device toolbar →
      **iPhone SE** (375×667). The footer Add-prose /
      Add-walkthrough CTAs stack vertically. The annotation
      row's three columns stack too (ply / text / remove
      each take the full width) so the text textarea has
      breathing room.
    - **Dark mode** — toggle theme. The PROSE textarea's
      focus ring picks up the dark-mode accent; the
      walkthrough annotations section's background darkens
      one step against the paper surface so the sub-section
      still reads as nested; kind tags keep their accent
      tint.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — all 62 lesson-blocks keys pick up their
      translations. The moves count + annotations count
      render Russian's `one/few/many/other` forms
      (`1 ход / 2 хода / 5 ходов`; `1 аннотация / 2
      аннотации / 5 аннотаций`) and Uzbek's `one/other`.
      The plural-aware `annotationsMaxReached` interpolates
      the `{max}` param ("Достигнут максимум (64).").

### #64 — Class detail UX — wire calendar day-selection filter (Phase 2d)

Phase 2d closes the loop opened by Phase 2b: the weekly
calendar strip's day-selection (`?day=YYYY-MM-DD` query param)
now actually scopes the three time-driven panels down to rows
that land on the pinned cell. Previously clicking a day on the
strip just highlighted the cell visually — the panels underneath
kept showing their full contents. Now a pinned day hides every
row whose timestamp doesn't land on that day (Asia/Tashkent
wall-clock), renders a tinted accent-soft banner above each
panel's toolbar announcing the filter + the per-panel item
count, and gives the viewer a single "Clear filter" button per
panel to strip the URL param. The filter applies on top of the
existing per-panel text search so the two compose cleanly
(a search for "Sicilian" + a pinned Friday yields Sicilian rows
on that Friday only). Empty-state copy gets a new `emptyDay`
variant per panel so a day with no matching rows reads
coherently ("No sessions on this day." rather than the generic
"No upcoming sessions.") without confusing the viewer into
thinking the class has no schedule at all.

Files:

- `apps/web/src/lib/class-detail-buckets.ts` — extended. Added
  exported `isOnDay(iso: string | null, ymd: string)` helper
  that wraps the existing `localIsoDate` so the bucketing
  helper and the day-filter share one canonical
  Asia/Tashkent-wall-clock comparison. Null-safe — a row with
  no timestamp returns `false` (homework with no `dueAt` can't
  match a specific calendar day). Also added
  `countOnDay<T>(items, getIso, ymd)` for the banner's item
  count.
- `apps/web/src/app/[locale]/admin/classes/[id]/day-filter-banner.tsx`
  — new `"use client"` component (~85 lines).
  `DayFilterBanner({day, count, onClear, locale})` renders an
  accent-tinted card with the pinned date ("Fri · 24 Apr") + a
  plural-aware item count on the left and a ghost-style
  "Clear filter" button on the right. `role="status"` +
  `aria-live="polite"` so screen readers announce the filter
  when it arrives without hijacking focus. Date formatting
  uses `Intl.DateTimeFormat(locale, {timeZone: "Asia/Tashkent",
  weekday: "short", day: "numeric", month: "short"})` — same
  rhythm the calendar strip uses for its cells — with the
  Russian "апр." trailing period stripped so the label reads
  cleanly against the surrounding chrome. i18n-agnostic at the
  call-site boundary: reads its strings from the shared
  `admin.classes.dayFilter` namespace so all three panels
  render identical copy without each owning duplicate keys.
  Deliberately does NOT own the `useRouter` round-trip — each
  panel passes in an `onClear` handler because each already
  has its own reason to update the URL (this keeps
  `day-filter-banner.tsx` a pure view).
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — wired the banner + filter into the existing Active tab
  branch. Reads `?day` via `useSearchParams()`; a strict
  `isValidYmd(rawDay)` regex (`/^\d{4}-\d{2}-\d{2}$/`) gates
  the read so a crafted URL like `?day=tomorrow` silently
  falls through to no-filter. The existing search `filtered`
  memo is chained through a new `dayFiltered` memo that
  applies `isOnDay(h.dueAt, dayFilter)`; the bucket renderer
  downstream consumes `dayFiltered` instead of `filtered`, so
  the Past / This week / etc. headings re-compute to reflect
  only the matching rows. The banner mounts above the
  toolbar-row just below the header. `onClearDay()` builds a
  new `URLSearchParams` from the current query string,
  `.delete("day")`s it, and calls
  `router.replace(url, { scroll: false })` so the clear
  doesn't teleport scroll position. Archived tab is left
  alone — the filter is intentionally scoped to the live
  Active surface, not the review-only archive.
- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-panels.tsx`
  — same pattern as homework. The timestamp accessor is
  `l.sessionStartsAt` (the lesson's anchored session start is
  its natural calendar landing); untimed lessons can never
  match a pinned day and are hidden while the filter is
  active.
- `apps/web/src/app/[locale]/admin/classes/[id]/schedule-panels.tsx`
  — same pattern with one structural difference: schedule
  groups sessions by ISO-week via `groupByIsoWeek` (not the
  `classifyTimestamp`-keyed bucketer the other two use). The
  day-filter is applied pre-`groupByIsoWeek` so matching
  sessions keep their week-group context — when the filter
  collapses the view to a single week, the "This week" /
  "Next week" week-heading highlight still fires if the
  pinned day lands inside today's ISO week, keeping the
  visual rhythm coherent with the calendar strip's today
  outline.
- `apps/web/messages/{en,ru,uz}.json` — added the shared
  `admin.classes.dayFilter.*` subtree (4 keys): `heading`
  (with `{date}` interpolation), `count` (plural-aware), `clear`,
  `clearAriaLabel` (with `{date}` interpolation). Russian uses
  the full `one/few/many/other` plural forms on the count;
  Uzbek uses `one/other`. Also added `emptyDay` per panel
  under `admin.classes.schedule.sessions`,
  `admin.classes.homework`, and `admin.classes.lessons` (3
  keys per locale × 3 locales = 9 strings) for the "pinned
  day but nothing matches" empty state. Key parity verified:
  en=645, ru=645, uz=645 (zero drift).
- `apps/web/src/app/globals.css` — appended a ~75-line block
  under a new header "Class detail: day-filter banner (Phase
  2d of the Option 4 UX overhaul)". Key primitives:
  `.day-filter` (flex-row with accent-soft tint + 1px
  accent-tinged border + 10px radius); `.day-filter__copy`
  (flex-column for heading + count); `.day-filter__heading`
  (13px / 600 weight); `.day-filter__count` (12px muted);
  `.day-filter__clear` (ghost button, `flex-shrink: 0` so
  the copy column eats any wrap). Responsive
  `@media (max-width: 560px)` rule stacks the copy + clear
  button into a column and left-aligns the button so it
  doesn't stretch edge-to-edge. Dark-mode override bumps the
  accent-soft fill percentages (10% → 14%, border 35% → 45%)
  so the tint stays legible against the darker paper.
  CSS brace balance verified: 761 open / 761 close.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0).

Deliberately scoped out:

- **Filtering the Past bucket** — homework / lesson panels
  render a collapsed `<details>` Past bucket below the live
  ones (Phase 1's scoped-out behaviour). When the day filter
  is active, the Past bucket's counter re-computes based on
  `dayFiltered` rows that also land in the past bucket; if
  zero, the entire `<details>` is omitted. No special-case
  work — the bucketer already does the right thing downstream
  of `dayFiltered`.
- **Clear-filter affordance on the calendar strip** — a
  pinned day can still be cleared from either (a) the strip's
  day-cell click (which toggles the selection on the same
  cell) or (b) "Jump to today" (which clears both `?week`
  and `?day`). The per-panel banner is redundant with those
  but lives closer to the filtered content, which is the
  point — the viewer doesn't have to scroll back up to
  unpin. Both paths converge on the same URL state.
- **Roster filtering** — the Roster panel has no temporal
  axis and is therefore immune to the `?day` param. Phase 1
  also scoped Roster out of per-panel search for the same
  reason.
- **Calendar dot highlight on the selected cell** — the
  Phase 2b strip already paints the selected cell with
  accent-soft fill. No visual change needed there.
- **URL-driven default on first paint** — opening
  `/en/admin/classes/<id>?day=2026-04-24` in a fresh tab
  renders with the filter already applied (server components
  + client components both read the same
  `useSearchParams()` / `searchParams` stream). No special-
  case code — the read paths are already URL-driven from the
  Phase 2b work.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as a school admin (`admin@demo.local` /
   `admin12345`) or a teacher assigned to a class.
3. Open a class that has at least:
    - One upcoming non-cancelled session this week.
    - One lesson anchored to that same session.
    - One homework with a `dueAt` on that session's day.
   Go to `/en/admin/classes/<classId>`.
4. Happy path — pin a day with matches:
    1. In the weekly calendar strip (below the Overview
       hero), click the day cell that has all three dots
       (session + lesson + homework).
    2. The cell fills with accent-soft green; URL gains
       `?day=YYYY-MM-DD`.
    3. Scroll down. Above each of the Homework, Lessons,
       and Schedule panel toolbars, an accent-tinted banner
       now reads **Filtered to Fri · 24 Apr** with an item
       count underneath (e.g. **1 item** / **3 items**)
       and a **Clear filter** ghost button on the right.
    4. The panels themselves now show ONLY rows that land
       on the pinned day. Homework's bucket headings
       re-compute (e.g. "This week" now reads "1 item"
       even if the class has 4 homework rows this week).
       The schedule collapses to a single ISO-week group.
5. Happy path — compose with search:
    1. Leave the day pinned. Type a partial title into the
       Homework panel's search (e.g. "tactic").
    2. The panel shows homework whose title matches AND
       lands on the pinned day. The banner's item count
       updates to reflect the composed filter.
    3. Clear the search — the full set of rows on the
       pinned day returns.
6. Happy path — clear per-panel:
    1. Click **Clear filter** on the Homework banner.
    2. URL sheds `?day`; all three banners disappear
       simultaneously (because they all read from the same
       param). All three panels return to their full
       contents. The calendar strip cell de-selects.
7. Happy path — clear via the calendar strip:
    1. Click a day on the strip. All three panels scope down.
    2. Click the same day again — selection toggles off;
       URL sheds `?day`; banners disappear; panels return to
       full contents.
    3. Click a different day — selection moves; panels
       re-scope.
    4. Click the **Jump to today** chip (appears if you've
       also paged weeks). URL sheds both `?week` and
       `?day`; banners disappear; strip returns to the
       current week.
8. Edge cases worth eyeballing:
    - **No matches on the pinned day** — navigate to a week
      where one day is empty (no dots) and click it. Strip
      highlights the selected cell; banners show
      **Filtered to ... · 0 items**; the panels render the
      new empty-state copy: Homework reads "No homework due
      on this day." (not the generic "No homework yet.");
      Lessons reads "No lessons on this day."; Schedule
      reads "No sessions on this day."
    - **Malformed URL param** — paste
      `/en/admin/classes/<id>?day=broken` into a fresh tab.
      Banners don't render; panels show their full
      contents; the strict YMD regex rejects the bad value
      silently.
    - **Deep link** — paste
      `/en/admin/classes/<id>?week=2026-04-20&day=2026-04-24`
      into a fresh tab. The strip renders on the 20-Apr
      week with Friday highlighted; all three banners
      render immediately with the correct item counts; the
      panels render their filtered contents on first paint
      (no hydration flash because the params are read on
      the server too).
    - **Archived homework/lessons tab** — flip **Show
      archived** on either the Homework or Lessons panel
      while a day is pinned. The archived list renders
      unfiltered (review-only archive isn't subject to the
      day filter by design); the banner disappears on the
      archived view. Flip back to active — banner + filter
      return.
    - **Past bucket collapse** — pin a day that only has
      overdue homework on it. The active Homework panel
      renders the Past bucket (expanded by default if it's
      the only bucket with content? actually still
      collapsed per Phase 1 spec — expand it to see the
      rows). Other buckets (This week / Next week / Later
      / No date) are omitted because zero matches.
    - **Schedule week coherence** — pin a day that lands
      inside the current ISO week. The schedule collapses
      to a single week group, and its week heading still
      shows the green-tinted "This week" sub-label —
      because the pinned day IS in this week, the
      highlight is still semantically correct.
    - **Responsive ≤ 560px** — DevTools → device toolbar →
      **iPhone SE** (375×667). The banner stacks: heading +
      count on top, Clear filter button underneath (left-
      aligned, not stretched). Touch targets stay ≥ 40px.
    - **Dark mode** — toggle theme. The accent-soft fill
      deepens (10% → 14%), the border tightens (35% →
      45%) so the banner stays legible against the darker
      paper. Ghost button inherits the existing dark-mode
      ghost treatment.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`**
      — banner heading, count (with Russian's full
      `one/few/many/other` plurals: "1 запись" / "2 записи"
      / "5 записей"), clear button, aria-label, and the
      three empty-day copies all pick up their
      translations. The date label's weekday + month
      abbreviation come from the locale (`ПТ · 24 АПР` /
      `JU · 24 APR`) with the trailing "." stripped in
      Russian.

### #62 — Class detail UX — weekly 7-day calendar strip (Phase 2b)

Phase 2b drops a horizontal 7-day calendar strip onto the class
detail page, mounted between the Phase 2a Overview hero and the
Phase 1 sticky sub-nav so the reader scans "what's happening this
week?" before drilling into panels. Each day cell carries up to
three coloured dots — green session, blue lesson (lessons anchored
to a session that starts that day), amber homework (homework with
a `dueAt` on that day). Today is outlined in accent-green; a
selected day (if any) fills with the accent-soft palette so the
cell reads as pressed. Arrow buttons step the window by one
calendar week in either direction, unbounded (a teacher can page
back years or forward into next term), and a "Jump to today"
chip appears whenever the viewer has navigated away from the
current week. State lives in the URL via two query params —
`?week=YYYY-MM-DD` anchors the Monday of the visible window,
`?day=YYYY-MM-DD` pins the selected day — so every view is
deep-linkable and survives a refresh. Arrow nav clears any
selection; "Jump to today" clears both; day-click toggles. All
transitions use `router.replace({ scroll: false })` so clicks
don't teleport the scroll position or pollute browser history.

Filter wiring is deliberately scoped to **Task #64** (Phase 2d):
this slice is visual-only. When a day is selected the other
panels still show their full contents; #64 will read `?day` and
filter Homework / Lessons / Schedule down to rows that land on
the pinned cell.

Files:

- `apps/web/src/lib/class-detail-buckets.ts` — extended. Promoted
  `localIsoDate` + `addDays` to exported functions (their docblocks
  now note they're shared with the calendar strip so the "today"
  math stays consistent across chrome — if these drifted, an item
  classified as "this week" in the bucketing helper could land on
  yesterday's calendar cell or vice versa). Added a new exported
  `isoWeekMondayYmd(yyyyMmDd)` helper that wraps the existing
  `isoDayOfWeek` cache to return the Monday of the ISO week
  containing a date. The schedule panel was already computing this
  locally via its own `isoWeekMondayFromYmd` — a future refactor
  can delete that duplicate now that the canonical helper lives
  here; kept the duplicate in place for this slice to hold the
  diff tight.
- `apps/web/src/app/[locale]/admin/classes/[id]/calendar-strip.tsx`
  — new `"use client"` component (~340 lines).
  `CalendarStrip({sessions, homework, lessons, locale})` reads
  `?week` / `?day` from `useSearchParams()` (with a strict
  `isValidYmd` regex so a crafted URL like `?week=garbage` falls
  back silently to today's week), computes the 7-day window via
  `addDays(monday, 0..6)`, and bucket-counts the three prop arrays
  by `localIsoDate` of their primary timestamp (`session.startsAt`
  / `lesson.sessionStartsAt` / `homework.dueAt`) into a
  `Map<string, DayTally>`. The range label ("20 – 26 Apr" vs
  cross-month "28 Apr – 4 May") is locale-aware via
  `Intl.DateTimeFormat` locked to `Asia/Tashkent`. Day cells use
  `aria-pressed={isSelected}` + `aria-current={isToday ? "date" :
  undefined}` so screen readers announce the state transitions;
  the count is folded into `aria-label` via a plural-aware
  `dayAriaLabel` key. Nav state is mutated via a single
  `pushParams({week?, day?})` helper that merges into the existing
  `searchParams` (so unrelated query params — e.g. a future
  `?tab=` from Phase 2c — survive round-trips), builds a typed
  `Route` cast (Next 15's `typedRoutes: true` wants a known-at-
  compile-time shape, and our URL is runtime-constructed), and
  calls `router.replace(url, { scroll: false })`.
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` — imports
  `CalendarStrip` and mounts it directly between `<ClassOverview>`
  and `<ClassDetailSubNav>`. Feeds it the already-fetched
  `sessions`, `activeHomework`, `activeLessons`, and `locale` — no
  new backend fetch.
- `apps/web/messages/{en,ru,uz}.json` — new
  `admin.classes.calendar.*` subtree (9 keys per locale):
  `ariaLabel`, `prevWeek`, `nextWeek`, `jumpToday`, `todayLabel`,
  `dayAriaLabel` (plural), `legendSession`, `legendLesson`,
  `legendHomework`. Russian uses the full `one/few/many/other`
  plural set on the day count; Uzbek uses `one/other`. Inserted
  immediately after `admin.classes.overview` so Phase 2 chrome
  keys stay grouped. Key parity verified: en=638, ru=638, uz=638
  (zero drift).
- `apps/web/src/app/globals.css` — appended a ~220-line block
  under a new header "Class detail: weekly 7-day calendar strip
  (Phase 2b of the Option 4 UX overhaul)". Key primitives:
  `.cal-strip` (flex-column paper-elevated card with 1px
  paper-edge border + 12px radius); `.cal-strip__head` (flex row
  with left-hand nav-group {prev / range label / next} and
  right-hand "Jump to today" chip or spacer placeholder to keep
  the head height stable while on the current week); 28px-square
  `.cal-strip__nav` arrow buttons with hover / focus-visible
  states; `.cal-strip__range` (Newsreader serif 15px, 11ch
  min-width so the label doesn't jitter as month transitions);
  pill-shaped dashed-border `.cal-strip__today` chip that swaps
  to a solid accent-soft fill on hover; CSS-grid
  `.cal-strip__days` (7 equal columns); `.cal-strip__day`
  (flex-column button, 72px min-height, weekday label + day
  number + dots row) with four variants:
  `.cal-strip__day--today` (accent outline + tinted fill),
  `.cal-strip__day--selected` (accent-soft fill, accent-ink text,
  solid accent-ink border), `.cal-strip__day--empty` (ink-muted
  text for days with zero activity). 6px round
  `.cal-strip__dot` primitive with three hues:
  `--session` = `--accent` (green, matches Phase 1's "this week"
  bucket heading), `--lesson` = `oklch(0.62 0.13 245)` (cool
  blue), `--homework` = `oklch(0.72 0.14 75)` (warm amber,
  urgent-warm against the green/blue pair).
  `.cal-strip__legend` row under a dashed top-border spelling out
  each dot colour. Responsive `@media (max-width: 560px)` rule
  compacts the head (range min-width → 9ch), day cells (72px →
  64px), weekday letter-spacing, and hides the legend row — the
  dot colours are self-explanatory in the tighter layout and the
  row chewed screen height. Dark-mode overrides flip the paper
  surface, bump the lesson + homework dot lightness for contrast
  against the darker paper, and tint the "Jump to today" chip
  with the dark-mode accent.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean
(EXIT=0). CSS brace balance verified: 752 open / 752 close.
i18n parity verified: en=638, ru=638, uz=638, zero drift.

Deliberately scoped out:

- **Filter wiring** — Task #64 (Phase 2d) reads `?day` and scopes
  the three time-driven panels. Kept out of this slice so each
  phase keeps a tight review surface: visual chrome first, then
  the cross-cutting filter pass.
- **Session past-week coverage** — the `sessions` prop comes
  from page.tsx's `?upcomingOnly=true` fetch, so paging back to
  a completed week shows empty session columns even when the
  class has met before. Homework + lessons are fetched
  archive-inclusive so past-dated rows DO show dots. Widening
  the sessions fetch would double the typical wire size of the
  class detail page just to light up historical session dots
  that the Schedule panel's Past bucket already surfaces one
  click away. If real-world usage shows teachers wanting
  historical recap at a glance, the cheapest fix is a separate
  `?week=` GET for the currently-viewed window rather than
  always loading everything — file as a follow-up only if
  needed.
- **Visual distinction for cancelled sessions** — cancelled
  sessions still count toward the session-dot tally so the
  strip stays consistent with Phase 1's sub-nav "N this week"
  count. Polish item: strike-through / dim tint for
  cancelled-only days. Not in v1.
- **Keyboard arrow-key navigation between day cells** — the
  buttons are reachable via `Tab`, which is enough for WCAG.
  A `roving tabindex` + left/right arrow-key pattern would be
  nicer but adds code without changing the information
  surfaced. Revisit if user testing shows friction.
- **Week-number label** — some calendars show "Week 17" next
  to the date range. Shelved; the ISO week number isn't a
  vocabulary Uzbek / Russian chess teachers reach for
  naturally.
- **Auto-refresh of "today"** — the strip computes `todayYmd`
  at render time and won't re-compute on a tab the user leaves
  open past midnight. Matches Phase 2a Overview and Phase 1
  sub-nav behaviour (also server-rendered / render-time
  computed). A `revalidatePath` on a timer or a thin client
  tick is a cheap follow-up if it proves annoying.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the
   frontend.
2. Sign in as a school admin (`admin@demo.local` / `admin12345`)
   or a teacher assigned to a class.
3. Open a class that already has:
    - At least one upcoming non-cancelled session this week.
    - At least one lesson anchored to that session.
    - At least one homework with a `dueAt` this week.
   Go to `/en/admin/classes/<classId>`.
4. Happy path — initial render:
    1. Below the Overview hero and above the sticky sub-nav, a
       paper-elevated card shows `‹  20 – 26 Apr  ›` in the top
       row with an invisible placeholder on the right (the
       "Jump to today" slot is empty because you're already on
       the current week).
    2. A 7-cell row beneath shows the weekdays MON..SUN (or
       localised equivalents), the day numbers in serif, and
       zero-to-three coloured dots beneath. The cell for today
       is outlined with a thin green border, a tinted fill, and
       its weekday label reads **TODAY** in uppercase.
    3. A dashed-border legend row under the cells reads:
       **● Session &nbsp; ● Lesson &nbsp; ● Homework due**.
5. Happy path — step by week:
    1. Click the right arrow. The range label updates to the
       next week (e.g. `27 Apr – 3 May`). The cells refresh —
       lessons + homework that landed in the new window appear
       as dots; sessions may be empty if the week is past the
       upcoming-only fetch's horizon.
    2. A **"Jump to today"** pill chip appears in the right
       slot of the head row. The URL grows a `?week=2026-04-27`
       query param.
    3. Click the left arrow twice. The range jumps to last week.
       URL `?week=2026-04-13`. Homework / lessons dots for past
       rows DO show; session dots are empty (see scoped-out
       note).
    4. Click **Jump to today**. URL clears to `/en/admin/classes/<id>`
       (no `?week`), range label returns to the current week,
       chip disappears.
6. Happy path — day selection:
    1. Click a day that has at least one dot. The cell fills
       with accent-soft green, the number + weekday go
       accent-ink. URL gains `?day=2026-04-22`.
    2. Refresh the page — selection survives (day stays
       highlighted).
    3. Click the same cell again — selection clears; URL sheds
       `?day`.
    4. Click a different day — selection moves. Paging by
       arrow nav clears `?day` automatically (a selection from
       a different week isn't meaningful once the viewer has
       moved on).
7. Edge cases worth eyeballing:
    - **Cross-month range label** — navigate to a week that
      straddles a month boundary (e.g. `28 Apr – 4 May`). The
      label reads with both month suffixes. On same-month
      weeks it collapses to `20 – 26 Apr`.
    - **Empty day** — a day with zero activity reads in
      `--ink-muted`; the dots row is still reserved (8px
      min-height) so the cell's vertical rhythm doesn't jump
      when activity appears on scroll.
    - **Malformed URL param** — paste `/en/admin/classes/<id>?week=totally-broken`
      into a new tab. Strip falls back silently to today's
      week (the `isValidYmd` regex rejects anything that
      isn't strict YYYY-MM-DD). Same for `?day=yesterday`.
    - **Unrelated query params preserved** — once Phase 2c
      lands a `?tab=` param, arrow nav / day click should
      leave it alone. Simulate now by appending `?foo=bar` to
      the URL and clicking an arrow — `?foo=bar` should still
      be there.
    - **Responsive ≤ 560px** — DevTools → device toolbar →
      **iPhone SE** (375×667). The head's range label shrinks
      to 14px with a 9ch min-width; day cells compact to 64px
      tall and lose the weekday letter-spacing; the legend
      row hides entirely (dot colours are self-explanatory in
      the tighter layout). Scroll isn't affected since the
      strip fits in a single row.
    - **Dark mode** — toggle theme. The paper-elevated surface
      flips to the dark variant; the amber homework dot
      brightens to `oklch(0.8 0.14 75)` for contrast; the
      **Jump to today** chip switches to the dark accent tint
      and reads against the darker paper.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`** —
      the **TODAY** uppercase label, arrow `aria-label`s, the
      **Jump to today** chip copy, the three legend items, and
      the day cell `aria-label` plural (e.g. Russian `1 событие
      / 2 события / 5 событий`) all pick up their translations.
      Weekday abbreviations come from the locale (`ПН ВТ СР …`
      / `DU SE CH …`). The range label's month abbreviation
      drops the trailing dot that some Intl locales emit
      (`.replace(/\.$/, "")`).
    - **Deep link** — paste `/en/admin/classes/<id>?week=2026-05-04&day=2026-05-06`
      into a fresh tab. The strip renders on the 4-May week
      with Wednesday 6 May pre-selected. Deep-link survives
      refresh; back button returns to the referrer URL rather
      than cycling through every calendar click (strip uses
      `router.replace`, not `push`).

### #61 — Class detail UX — Overview widget (Phase 2a)

Phase 2a lands the first above-the-fold "what's on my plate?" surface
on the class detail page: a two-column hero card that mounts between
the page header and the Phase 1 sticky sub-nav. Left column answers
"when's the next session?" with a relative-time headline ("In 4 h" /
"Tomorrow" / "In 3 days") plus a dense sub-line carrying the full
Asia/Tashkent timestamp ("THU · 21 APR · 18:00") that doubles as a
link to `#schedule`. Right column answers "how much homework is due
this week?" with a plural-aware count and a CTA that jumps to
`#homework`. All data is computed client-side (server component in
page.tsx) from rows the panels already fetch — no new backend
endpoint, no new controller, no new DTO. The original task brief
included a backend rollup endpoint; skipped because everything
needed is already on the wire via the existing `?upcomingOnly=true`
sessions fetch + the archived-aware homework fetch. Saves one
round-trip and ~300 lines of backend plumbing.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/class-overview.tsx`
  — new server component (~200 lines). `ClassOverview({nextSession,
  homeworkDueThisWeek, locale})` renders a `<section
  class="class-overview">` with two cells. Relative-time math is a
  five-bin classifier: `<2 min` → "Starting now", `<60 min` → "In N
  min", `<24 h` → "In N h", next calendar day → "Tomorrow", 2–6
  calendar days → "In N days", else fall through to the full
  timestamp string. "Calendar day" is measured in Asia/Tashkent via
  the same `localIsoDate` + UTC-noon Date-math pattern as
  `class-detail-buckets.ts`, so a session at 00:30 tomorrow from a
  call made at 23:45 today reads as "Tomorrow", not "In 45 min".
  The full timestamp is uppercased-short "THU · 21 APR · 18:00" to
  match the rhythm of the student list date ribbons elsewhere on
  the site.
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` — added
  `ClassOverview` import, computed `const nextSession =
  sessions.find((s) => s.status !== "CANCELLED") ?? null` (the
  sessions fetch already filters `startsAt >= now()` and sorts asc,
  so the first non-cancelled row is the next scheduled meeting by
  definition; in-progress sessions are excluded and flagged with a
  comment for the Phase 2b calendar strip to handle). Mounted
  `<ClassOverview>` between the page header and the
  `<ClassDetailSubNav>` so the hero reads before the jump-nav.
- `apps/web/messages/{en,ru,uz}.json` — new
  `admin.classes.overview.*` subtree (12 keys per locale, 617 → 629
  total, parity verified via set-symmetric-difference script):
  `ariaLabel`, `nextSessionLabel`, `nextSessionEmpty`, `relativeNow`,
  `relativeMinutes` (plural), `relativeHours` (plural),
  `relativeTomorrow`, `relativeDays` (plural), `homeworkDueLabel`,
  `homeworkDueEmpty`, `homeworkDueCount` (plural), `viewHomeworkCta`.
  Russian uses the full `one/few/many/other` plural set for the
  three count-bearing keys; Uzbek uses `one/other`. Inserted
  immediately after `admin.classes.buckets` so the Phase 1 + Phase
  2 chrome keys sit together in the file.
- `apps/web/src/app/globals.css` — appended a ~110-line block under
  a new header "Class detail: Overview widget (Phase 2a of the
  Option 4 UX overhaul)". Key primitives: `.class-overview`
  (CSS-grid `1fr 1fr` with 20px gap, paper-elevated surface with a
  1px paper-edge border and a 12px radius), `.class-overview__cell`
  (flex-column, 6px gap), `.class-overview__cell--homework` (dashed
  left-border divider — 20px padding), `.class-overview__eyebrow`
  (11px uppercased letter-spaced label, `--ink-muted`),
  `.class-overview__headline` (22px Newsreader serif 600 weight),
  `.class-overview__headline--empty` (italicised + muted for the
  "No upcoming sessions" / "Nothing due this week" empty states),
  `.class-overview__sub` (13px `--ink-soft`),
  `.class-overview__link` (accent-ink text, dashed-on-hover
  bottom-border for the schedule-timestamp + CTA anchors).
  Responsive `@media (max-width: 560px)` rule stacks the two cells,
  flips the divider from left-border to top-border, and shrinks the
  headline from 22px to 20px. Dark-mode variants keep the
  paper-elevated surface + nudge the link colour to `--accent` so
  the hover underline reads against the darker paper.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean (EXIT=0).
CSS brace balance verified: 711 open / 711 close.

Deliberately scoped out:

- **Backend rollup endpoint** — the original task brief included a
  `GET /api/v1/admin/classes/{id}/overview` that would return a
  tidy `{nextSession, homeworkDueThisWeek, lessonsThisWeek,
  overdueCount}` tuple. Skipped because the data is already on the
  wire from the existing three fetches (sessions, homework,
  lessons) — adding an endpoint would duplicate the rollup logic
  between frontend and backend. The `countThisWeek` helper from
  Phase 1 does the homework counter in ~5 lines of JS. If Phase
  2b needs per-day tallies for the calendar strip, that's when a
  dedicated rollup pays for itself — file under Task #62 scope
  if so.
- **Overdue homework counter** — user explicitly dropped this from
  the widget content ("just next session + homework due this
  week, nothing else"). The Past bucket in the Homework panel
  already surfaces overdue rows one click away. A badge on the
  hero is easy to add later when we've validated teachers
  actually want it at the top.
- **Lessons-this-week counter** — same reason; user opted out of
  the "Lessons with a session this week" tile. Lesson completion
  is self-reported + revisitable, so "how many lessons are in
  flight this week" is a less urgent question than "when's my
  next class?".
- **Live relative-time tick** — the "In 4 h" label is computed at
  server-render time and won't update on a tab the user leaves
  open. Matches how the sub-nav counts work (also server-rendered)
  and the temporal-bucket section headings. A `setInterval`
  refresh + hydration boundary would add complexity for little
  gain; if it proves annoying, a `revalidatePath` every N minutes
  or a thin client island that re-formats the timestamp is a
  cheap follow-up.
- **In-progress session banner** — a session whose `startsAt` is
  in the past but `endsAt` is in the future doesn't appear in the
  upcoming-only list, so the hero shows the *next* session rather
  than the in-progress one. Acceptable trade-off for 2a; the
  Phase 2b calendar strip (#62) can surface in-progress sessions
  directly on the "today" cell and the hero can remain
  forward-looking.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the frontend.
2. Sign in as a school admin (`admin@demo.local` / `admin12345`)
   or a teacher assigned to a class.
3. Open a class that already has:
    - At least one upcoming non-cancelled session (create one via
      **Add session** if needed — pick a time 2–3 days out so the
      hero lands in the "In N days" bin).
    - At least one homework with a due date this week (today or
      next 7 days in Asia/Tashkent); create via **New homework**
      if needed.
   Go to `/en/admin/classes/<classId>`.
4. Happy path — hero reads top-to-bottom:
    1. Below the page title + status pill, and before the sticky
       sub-nav, a two-column card shows:
       - Left: **NEXT SESSION** eyebrow in uppercased small caps,
         a Newsreader serif headline "In 3 days" (or "Tomorrow" /
         "In 4 h" depending on how close), and a muted sub-line
         "THU · 21 APR · 18:00" that's an underlined-on-hover
         link pointing at `#schedule`.
       - Right: **HOMEWORK DUE THIS WEEK** eyebrow, headline
         "3 assignments" (plural-aware), and a "Review homework
         →" CTA that's also an anchor to `#homework`.
    2. Click the timestamp on the left — the page smooth-scrolls
       down to the Schedule panel (the Phase 1
       `scroll-margin-top: 80px` clears the sticky sub-nav).
    3. Click **Review homework →** on the right — page jumps to
       the Homework panel.
5. Happy path — relative-time bins:
    1. Create a session starting in ~30 minutes (edit the time on
       an existing row if easier). Refresh the page — the
       headline now reads **In 30 minutes** (with Russian plural
       `one/few/many/other` on `/ru/...` — 1 минуту, 2 минуты,
       5 минут).
    2. Edit the session to start in 3 hours. Refresh → **In 3
       hours**.
    3. Edit to 30 hours out (tomorrow). Refresh → **Tomorrow**.
    4. Edit to 4 days out. Refresh → **In 4 days**.
    5. Edit to 10 days out. Refresh → the headline falls through
       to the full timestamp "MON · 30 APR · 18:00" (no separate
       bin past 6 days — the sub-line would otherwise duplicate).
6. Happy path — empty states:
    1. Open a class with no upcoming sessions (archive / delete
       all future sessions first). Hero left cell shows **No
       upcoming sessions** in italicised muted ink; no sub-line,
       no link.
    2. Open a class with no homework due this week (move all due
       dates past next Sunday). Hero right cell shows **Nothing
       due this week** in the same italic treatment.
    3. A class with both empty cells still renders the hero card
       (so the chrome is consistent) — both cells show the
       italicised empty copy.
7. Edge cases worth eyeballing:
    - **Cancelled sessions skipped** — create an upcoming session
      and mark it `CANCELLED` via the schedule panel. Create a
      second session 2 hours later. Hero shows the second
      session, not the cancelled one. The `sessionsThisWeek`
      sub-nav badge counts the cancelled one (because it's still
      "scheduled this week" per the bucketing helper) — that's
      a conscious Phase 1 decision; Phase 2b will revisit the
      cancelled-row treatment across the page.
    - **In-progress session** — start a session's wall-clock
      time 5 minutes ago with `endsAt` 40 minutes from now. Hero
      **skips it** and shows the next upcoming session instead
      (in-progress rows aren't in the `?upcomingOnly=true`
      result set). The Schedule panel still shows them under
      "This week" with the green heading.
    - **Starting now** — fiddly to reproduce; the easiest way is
      to set a session's `startsAt` to `now() + 30 seconds` and
      refresh immediately. Headline reads **Starting now**.
    - **Tomorrow boundary** — a session at 23:45 tonight and one
      at 00:15 tomorrow night both read differently: the first
      is **In 23 hours**, the second is **Tomorrow** (because
      the calendar-day classifier is measured in Asia/Tashkent,
      not raw 24-hour chunks). Verify by seeding both and
      refreshing over the midnight boundary.
    - **Homework count plural forms** — seed 1, 2, and 5
      homework rows due this week. On `/ru/admin/classes/...`,
      verify "1 задание" / "2 задания" / "5 заданий" render
      correctly (Russian `one/few/many/other`). On
      `/uz/admin/classes/...`, verify "1 ta topshiriq" /
      "2 ta topshiriq" — Uzbek uses `one/other` and the `ta`
      particle is stable across both forms.
    - **Responsive ≤ 560px** — DevTools → device toolbar →
      **iPhone SE** (375×667). The hero stacks to a single
      column, the left-divider on the homework cell flips to a
      dashed top-border with 14px of padding, and the headline
      shrinks from 22px to 20px. Anchor links still jump to
      their panels; Phase 1's 96px `scroll-margin-top` bump
      clears the compacted sub-nav.
    - **Dark mode** — toggle theme. Hero picks up the darker
      paper-elevated surface; the anchor link underlines + text
      switch to the dark-mode accent colour so the dashed hover
      underline stays legible.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`** —
      eyebrow labels, headlines, CTA copy, and both empty-state
      strings all pick up their translations. The weekday +
      month abbreviations in the sub-line come from the locale
      (`ЧТ · 21 АПР · 18:00` / `PAY · 21 APR · 18:00`), not the
      English ones.
    - **Deep link** — paste `/en/admin/classes/<id>#schedule`
      into a fresh tab. The hero renders above the sticky
      sub-nav; the page scrolls so the Schedule heading sits
      just below the sub-nav. Both anchor IDs still work.

### #60 — Class detail UX — temporal buckets + sticky sub-nav + search (Phase 1)

First of two phases in the class detail page UX overhaul (Option 4,
"cheap wins first, then calendar"). Phase 1 lands the low-risk, no
backend churn chunk that gives the page teeth without adding new
surfaces: a shared temporal-bucket helper, a sticky sub-nav with jump
links + counts, a per-panel text search, anchor IDs + scroll-margin-top
on every section, and a Past-bucket that collapses by default so
overdue homework / finished sessions stay one click away without
eating scroll space. No backend changes; all bucketing is client-side
Asia/Tashkent wall-clock math. Panel hierarchy on the page flipped
to Roster → **Homework → Lessons → Schedule** so the data hierarchy
reads bottom-up from the foundation (schedule ⊃ lesson ⊃ homework)
while the most-actionable / time-sensitive surface sits at the top.
Phase 2 (Tasks #61-#64) layers the Overview widget, weekly calendar
strip, tab-based IA, and calendar day-selection filter on top — the
anchor IDs (`#roster` / `#homework` / `#lessons` / `#schedule`)
introduced here stay stable so any bookmarks made now keep working
through the Phase 2 IA shift.

Files:

- `apps/web/src/lib/class-detail-buckets.ts` — new shared helper
  (~200 lines). Exports `CLASS_DETAIL_TZ`, `TemporalBucket`
  (`"thisWeek" | "nextWeek" | "later" | "noDate" | "past"`),
  `BUCKET_ORDER`, `BucketGroup<T>`, `classifyTimestamp`,
  `bucketByTimestamp`, `countThisWeek`. All math is done in
  Asia/Tashkent wall-clock — the docstring explicitly notes
  Uzbekistan's no-DST-since-2005 regime, so a fixed +05:00 is
  safe. ISO dates are formatted via `Intl.DateTimeFormat("sv-SE",
  {timeZone: CLASS_DETAIL_TZ, ...})` because sv-SE's short form
  already matches ISO-8601 (no manual part reassembly). Weekday
  lookup is cached in a module-level `Map<string, number>` so
  three panels asking for "today" on the same render pay one
  Intl round-trip. Today's items always land in `thisWeek`
  regardless of the time-of-day having passed — spec'd behaviour
  ("a 4pm class is still 'this week' at 5pm").
- `apps/web/src/app/[locale]/admin/classes/[id]/sub-nav.tsx` — new
  client component. `ClassDetailSubNav({counts})` renders a
  `<nav>` with four `<a>` links (`#roster` / `#homework` /
  `#lessons` / `#schedule`), a count badge per link, and optional
  hint pills for **N due this week** (homework) / **N this week**
  (schedule). Uses plain hash-fragment navigation (no client-side
  router code) so the page stays lean. `SubNavCounts` type
  exported for the page to feed from already-fetched data.
- `apps/web/src/app/[locale]/admin/classes/[id]/bucketed-list.tsx`
  — new generic renderer. `BucketedList<T extends {id: string}>`
  takes `{groups, tBuckets, renderRow, listClass?}`. Non-past
  buckets render as `<section>` with header + count; the `past`
  bucket wraps in `<details>` so it's collapsed by default. Used
  by Homework + Lessons (both render `<ul>` of cards). Schedule
  has its own ISO-week grain so it decorates week groups directly
  (see below) without going through this helper.
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` — added
  anchor wrappers (`<div id="roster"|"homework"|"lessons"|"schedule"
  className="class-detail__anchor">`) around the four existing
  panels. Computes sub-nav rollups (`rosterCount =
  teachers.length + students.length`, `homeworkDueThisWeek =
  countThisWeek(activeHomework, h => h.dueAt)`, `sessionsThisWeek
  = countThisWeek(sessions, s => s.startsAt)`) from the same data
  the panels render, so the badges match exactly. Mounts
  `<ClassDetailSubNav counts={...}>` between the page header and
  the panels. Panel order reshuffled to Roster → **Homework →
  Lessons → Schedule** with an inline comment spelling out the
  "most actionable at top, data hierarchy bottom-up" rationale.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — added search + bucket integration. Local `query` state, lowercase
  substring match on `title + instructions`, `emptySearch` copy when
  a query filters everything out. Active rows pass through
  `bucketByTimestamp(filtered, h => h.dueAt ?? null)` → rendered via
  `<BucketedList>`. Archived rows stay a flat `<ul>` — archived is
  review-only and newest-first is the right order there. Search
  toolbar is only rendered when `rows.length > 0` so the empty
  state still reads cleanly.
- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-panels.tsx`
  — same pattern as homework, with the timestamp accessor flipped
  to `l => l.sessionStartsAt ?? null` (the session anchor is the
  lesson's natural due date; untimed lessons land in `noDate`).
- `apps/web/src/app/[locale]/admin/classes/[id]/schedule-panels.tsx`
  — added a notes search (`s => (s.notes ?? "").toLowerCase().
  includes(q)`). Extended `SessionGroup` with optional `highlight:
  "this-week" | "next-week"` + `subLabel?: string`. New
  `labelWeekGroups(groups, t)` helper stamps the Monday-anchored
  group keys that match today's ISO week + the one after with a
  "This week" / "Next week" subLabel + modifier class. Helper
  `isoWeekMondayFromYmd` + `addDaysYmd` mirror the pattern in
  `class-detail-buckets.ts` (UTC-noon Date math to dodge DST).
  Kept the existing ISO-week grouping (reads much better than a
  flat list for a full term) — decoration is additive, no data
  reshape.
- `apps/web/messages/{en,ru,uz}.json` — new subtrees:
  - `admin.classes.subnav.*` (7 keys: `ariaLabel`, `roster`,
    `homework`, `lessons`, `schedule`, `dueThisWeek` plural,
    `scheduleThisWeek` plural).
  - `admin.classes.buckets.label.*` (5 keys: `thisWeek`,
    `nextWeek`, `later`, `noDate`, `past`).
  - `admin.classes.homework.*` additions: `emptySearch`,
    `searchLabel`, `searchPlaceholder`.
  - `admin.classes.lessons.*` additions: `emptySearch`,
    `searchLabel`, `searchPlaceholder`.
  - `admin.classes.schedule.sessions.*` additions: `emptySearch`,
    `searchLabel`, `searchPlaceholder`, `thisWeekBadge`,
    `nextWeekBadge`.
  Russian uses `one/few/many/other` on the two plural sub-nav
  hints; Uzbek uses `one/other`. Parity verified: en=617, ru=617,
  uz=617.
- `apps/web/src/app/globals.css` — appended ~300 lines under a new
  block header "Class detail: sticky sub-nav, anchor margins,
  per-panel search, and temporal bucket rhythm. Phase 1 of the
  Option 4 UX overhaul". Key primitives:
  - `.class-detail__anchor { scroll-margin-top: 80px; }` +
    `@media (max-width: 560px)` bumps to 96px to clear the
    compacted sub-nav.
  - `.class-subnav` (`position: sticky; top: 0; z-index: 30; …`)
    + `__list`, `__item`, `__link`, `__label`, `__count`, `__hint`,
    `__hint--due` (amber-tinged). Hover/focus states match the
    existing ghost-button palette.
  - `.hw-toolbar` + `.hw-search` + `.hw-search__input` (reused by
    Homework, Lessons, and Schedule so the search chrome stays
    consistent across the three panels).
  - `.sr-only` visually-hidden helper (the search `<label>` uses
    it so screen readers still read "Search homework" without
    adding visual clutter next to the input placeholder).
  - `.hw-buckets` (flex-column with 16px gap between bucket
    sections), `.hw-bucket`, `.hw-bucket__header`,
    `.hw-bucket__summary` (for the `<details>` past bucket),
    `.hw-bucket__heading`, `.hw-bucket__count`. Bucket-specific
    modifiers tint the heading: `.hw-bucket--this-week` picks up
    `var(--color-accent)` (the same green the "This week" chip
    uses) and `.hw-bucket--past` goes `var(--color-ink-muted)` so
    the collapsed summary reads as secondary. `.hw-list--bucket`
    is a list reset for lists nested inside bucket sections.
  - `.sched-week--this-week .sched-week__title` mirrors the
    homework accent-green tint on the current ISO-week group;
    `.sched-week__sub` styles the " · This week" sub-label that
    sits after the date range on the week heading.
  - Narrow-viewport overrides (`@media (max-width: 560px)`) hide
    the `__hint` labels on sub-nav (count badges stay), compact
    the links' inline padding, and bump `scroll-margin-top`.
  - `[data-theme="dark"]` overrides for sub-nav background, count
    badges, and hint pills so the sticky bar stays legible
    against the dark paper surface.

Typecheck: `npx tsc --noEmit` in `apps/web` passes clean.

Deliberately scoped out:

- **Overview widget** — hero card at the top of the page with
  "next session in X · Y homework due this week · Z unread
  lessons". Requires backend rollup endpoints (cheapest: one
  `/api/v1/admin/classes/{id}/overview` that returns the three
  counters + nextSession) — file under Task #61.
- **Weekly 7-day calendar strip** — horizontal strip above the
  panels showing each day's session dot + due-count badge. Needs
  `/overview` first so it knows what to tally per day. Task #62.
- **Tab-based IA** — replace the long-scroll layout with Overview
  / Homework / Lessons / Schedule tabs driven by a `?tab=` query
  param. The anchor IDs here map 1:1 to future tab slugs. Task #63.
- **Calendar day-selection filter** — clicking a day on the
  weekly strip scopes all panels to that single day. Task #64.
- **Backend rollup endpoints** — Phase 1 deliberately uses only
  data the page was already fetching. No new controllers /
  services / DTOs. If Phase 2 needs per-day counters, they'll
  land in Task #61.
- **Roster panel search / bucketing** — roster is a fixed,
  small list with no temporal axis. Leaving it flat keeps the
  visual rhythm honest ("search" = "search for time-driven
  things").

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and `cd apps/web &&
   pnpm --filter @chess-edu/web dev` (port 3100) for the frontend.
2. Sign in as a school admin (`admin@demo.local` / `admin12345`)
   or a teacher assigned to a class.
3. Open a class that already has:
    - At least one session today + one session in the next 7 days
      (create via **Add session** if needed).
    - At least one homework due today / in the next 7 days, one
      with no due date, and one overdue (edit an existing row's
      due date to the past via Edit).
    - At least one lesson anchored to a future session + one
      anchored to a past session + one untimed.
   `/en/admin/classes/<classId>`.
4. Happy path — sticky sub-nav:
    1. At the top of the page, below the title, a sticky bar shows
       four jump links: **Roster N · Homework N (· N due this
       week) · Lessons N · Schedule N (· N this week)**. The
       due-this-week hint only renders when the count is > 0.
    2. Scroll down past the roster. The bar stays pinned to the
       top of the viewport.
    3. Click **Schedule** on the bar — the page jumps to the
       Schedule panel with its heading clear of the sticky bar
       (the `scroll-margin-top` is doing its job). Hit the back
       button or `Alt+←` — URL fragment restores to `/class/<id>`
       (no fragment pushed because it's a hash jump, not a router
       nav).
    4. Click **Homework** — the bar jumps to the Homework heading.
       Copy the URL — it should be `/en/admin/classes/<id>#homework`.
       Paste into a new tab — the page loads and auto-scrolls to
       the Homework panel on first paint.
5. Happy path — temporal buckets (Homework):
    1. The Homework panel now renders sections instead of a flat
       list: **This week (N) · Next week (N) · Later (N) · No
       date (N)**. The This week heading is tinted the kit's
       accent green.
    2. **Past** sits below the others as a collapsed `<details>`
       with a chevron / summary "Past (N)". Click to expand — the
       overdue rows appear. Click again to collapse.
    3. Empty buckets are not rendered (e.g. if there are no "Next
       week" rows, that section isn't there at all).
6. Happy path — per-panel search (Homework):
    1. Type two or three letters of a homework title into the
       search box above the list (e.g. "sic" for a "Sicilian
       tactics" homework). The list filters in real time; the
       buckets re-compute so only the matching rows show, and
       empty buckets disappear.
    2. Clear the box — all rows come back.
    3. Type something that matches nothing (e.g. "zzzzz") — the
       list shows the **"No homework matches this search."**
       empty-search copy (leaves the "Show archived" toggle
       alone).
    4. Search also matches on instructions body, not just title —
       put a distinctive word into one row's instructions, search
       for it, verify that row surfaces.
7. Happy path — lessons + schedule:
    1. Lessons panel uses the same pattern, keyed on each lesson's
       anchored session start (`sessionStartsAt`). Untimed lessons
       land in **No date**.
    2. Schedule panel keeps its per-week grouping (Monday-anchored
       labels like "20 Apr – 26 Apr"). The week that contains
       today now shows a green-tinted heading + a " · This week"
       sub-label; the week after gets " · Next week". Older /
       further-out weeks render unchanged.
    3. Schedule search matches on session notes (the only free-
       text field sessions carry).
8. Edge cases worth eyeballing:
    - **Archived homework/lessons** — flip **Show archived** on
      either panel. The list renders flat (no bucketing), with
      an "Archived" pill on each row. Search still works but
      buckets aren't applied (archived is review-only, newest-
      first is the right order).
    - **No rows at all** — open a fresh class with no homework /
      lessons / sessions. Sub-nav shows each section with a `0`
      count. Panels render the stock empty-state copy; the
      search toolbar is NOT rendered (gate is `rows.length > 0`).
    - **Roster not searchable** — the roster panel has no search
      toolbar. This is intentional; rosters are small, fixed
      lists.
    - **Jackson non_null gotcha** — create a fresh homework with
      a due date set to tomorrow. It should land in **This week**
      (tomorrow is within the same ISO week). Now create a second
      homework leaving the due date blank — it should land in
      **No date**, not Past or This week. (Uses `== null`
      equality in the classifier to handle `undefined` coming
      from Jackson's default-non-null serialisation.)
    - **Today boundary** — a session scheduled for 4pm today
      should still appear in the schedule's current-week group
      with the green "This week" sub-label, even at 5pm. (Spec'd
      behaviour: today always counts as "this week" regardless
      of time-of-day.)
    - **Responsive ≤ 560px** — open DevTools → device toolbar →
      **iPhone SE** (375×667). Sub-nav collapses — the hint
      pills (" · 3 due this week") hide, counts + labels stay.
      Bucket sections stack with tighter padding. Anchor jump
      still clears the compact sub-nav (96px scroll-margin).
    - **Dark mode** — toggle theme. Sub-nav picks up a slightly
      darker paper surface; count badges + hint pills flip to
      dark-friendly variants. Bucket headings' accent-green
      stays saturated.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`** —
      sub-nav labels, bucket headings, search placeholders, and
      the "This week" / "Next week" sub-labels on the schedule
      panel all pick up their translations. The two plural
      hints (`dueThisWeek` / `scheduleThisWeek`) use Russian
      `one/few/many/other` — seed 1, 2, and 5 sessions in the
      current week and verify "1 на этой неделе" / "2 на этой
      неделе" / "5 на этой неделе" render correctly.
    - **Deep link** — paste `/en/admin/classes/<id>#lessons` into
      a fresh tab. The page loads, the sub-nav highlights no
      particular link (anchors don't toggle state — they just
      jump), and the viewport scrolls so the Lessons heading sits
      just below the sticky bar.

### #53 — Frontend author: Lessons panel on class detail (metadata only)

First frontend slice of the Lessons feature. Mounts a Lessons panel
below the Homework panel on the class detail page that reuses the
same visual grammar (card + list of rows + inline new/edit forms
+ native-confirm archive + expandable sheet) against the
`/api/v1/admin/classes/{classId}/lessons` surface from #51. Scoped
deliberately to metadata only: title / instructions / session
anchor / clearSession / archive. **The block editor is NOT in this
slice** — it's split across #54 (PROSE + BOARD_WALKTHROUGH) and
#55 (MCQ + VIDEO). The form sends no `resources` field on create
and omits it on PATCH, so the backend's authored blocks are left
untouched whenever a future block editor hasn't populated the
hidden `resources` input. Two contract differences from the
homework twin worth flagging:

- **No `dueAt` anywhere.** Lessons are revisitable study material
  — no due-date field, no due-date pill in the row meta, no
  `removeDueDate` checkbox on edit.
- **Completion sheet is READ-ONLY.** The backend only exposes
  student self-service endpoints for lesson completion
  (`PUT /api/v1/me/lessons/{id}/completion`). There's no admin
  override endpoint, so the sheet renders a flat list of
  `(displayName, status chip, markedAt)` tuples with no chips /
  buttons to mutate. A comment on the action module (`// No admin
  override…`) calls this out so the next agent doesn't try to
  wire a write action without first adding the backend surface.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-actions.ts`
  — new server actions module (~345 lines). Types:
  `LessonCompletionStatus`, `LessonSummary`, `LessonCompletionView`,
  `LessonResourceView`, `LessonDetail`, `LessonActionState`,
  `LessonDetailResult`, `ParsedResource`, `ResourcesParse`.
  Actions: `readLessonDetailAction` (lazy fetch on sheet expand),
  `createLessonAction`, `updateLessonAction`, `archiveLessonAction`.
  Error-status mapping (`0/401/403/404/409/400-422 → union`) and
  `revalidateDetail(locale, classId)` pattern mirror the homework
  twin line-for-line. `parseResources(formData)` implements the
  three-state PATCH convention (missing key → null, empty → [],
  list → replace) so future block editors can drop in without
  touching this module.
- `apps/web/src/app/[locale]/admin/classes/[id]/lesson-panels.tsx`
  — new client component (~683 lines). Components: `LessonPanels`
  (outer card, active / archived toggle, Add button gate),
  `NewLessonForm`, `LessonRow` (meta strip: session pill / created
  / blocks count / read-unread tally + truncated instructions
  preview), `Tally` (read vs. unread), `EditLessonForm` (with the
  same empty-defaultValue-means-keep trick as homework for
  instructions), `ArchiveButton` (native `window.confirm` gate),
  `CompletionSheet` (read-only list of per-student chips +
  markedAt timestamp), `SessionSelect` helper. Jackson non_null
  gotcha handled via `archivedAt != null` on the row (same #25
  fix). Module-level `createWasSubmitted` / `editWasSubmitted`
  refs close the forms on success via the same
  `useActionState`-between-renders trick the homework twin uses.
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx`
  — added `LessonPanels` + `LessonSummary` imports, an 8th
  parallel fetch
  (`apiFetch<LessonSummary[]>(…/lessons?includeArchived=true)`)
  and an `activeLessons` / `archivedLessons` split keyed on loose
  nullish equality. Mounted `<LessonPanels>` below
  `<HomeworkPanels>` with the same `canEdit={canMarkAttendance}`
  flag so assigned teachers get the same surface as admins.
- `apps/web/messages/{en,ru,uz}.json` — new
  `admin.classes.lessons.*` subtree (50 keys per locale, key
  parity verified via script: `en: 594 ru: 594 uz: 594` with zero
  missing). Covers:
  - Card chrome (title / addCta / hideAddForm / showArchived
    {count} / hideArchived / empty / emptyArchived).
  - `form.*` — titleLabel / titlePlaceholder /
    instructionsLabel / instructionsPlaceholder /
    instructionsKeepPlaceholder / instructionsEditHint /
    sessionLabel / sessionNone / clearSession / blocksHint /
    blocksEditHint / addCta / adding / saveCta / saving /
    cancel.
  - `row.*` — archivedTag / sessionOn {when} / created {when} /
    blocks (plural {count}) / tallyRead / tallyUnread /
    tallyAria / expandSheet / collapseSheet / edit / cancelEdit
    / archive / archiving / archiveConfirm.
  - `sheet.*` — loading / empty / status.READ /
    status.NOT_STARTED / status.UNMARKED / markedAt {when}.
  - `error.*` — invalid / unauthorised / forbidden / conflict
    / notfound / network / unknown.
  Russian uses `one/few/many/other` plural forms (e.g. the
  blocks count); Uzbek uses `one/other`.
- **No CSS changes.** The panel reuses the homework /
  schedule primitives (`.sched-card`, `.sched-card__header`,
  `.sched-empty`, `.sched-form`, `.sched-field`,
  `.sched-form__grid`, `.sched-form__footer`,
  `.sched-form__error`, `.hw-card`, `.hw-list`, `.hw-row`,
  `.hw-row__*`, `.hw-meta-item`, `.hw-meta-item--session`,
  `.hw-tally`, `.hw-tally__item`, `.hw-chip`, `.hw-chip--done`,
  `.hw-chip--skipped`, `.hw-chip--readonly`, `.hw-sheet`,
  `.hw-sheet__*`, `.hw-form`, `.hw-field-hint`,
  `.hw-field--check`, `.hw-archived-pill`,
  `.hw-row__instructions`). Same visual rhythm keeps the page
  coherent; future slices can add a `.lesson-*` namespace if
  lesson-specific affordances diverge.

Typecheck: `pnpm --filter @chess-edu/web typecheck` (equivalent to
`tsc --noEmit` in `apps/web`) passes clean against the current
codebase.

Deliberately scoped out:

- **Block editor** — authored content (PROSE / BOARD_WALKTHROUGH /
  MCQ / VIDEO) is Tasks #54 + #55. The `parseResources` helper +
  hidden-form-field convention is already in place so the editors
  can slot in without touching this action module. Until then,
  the list row shows a `blockCount` pulled from the backend
  summary — so an admin who created a lesson entirely via the
  API (with `resources: [...]` in the POST body) still sees the
  correct count in the UI.
- **Per-student override of completion** — no backend endpoint
  exists (by design in v1: completion is self-reported only).
  Chips in the sheet are visual-only; the `aria-pressed` + colour
  treatment indicates the current status but there's no click
  handler.
- **Due-date surface** — not applicable; lessons are revisitable.
  If a future "scheduled release" feature lands, the action
  module has a `TZ_OFFSET` constant stubbed (with a
  `void TZ_OFFSET;` reference to suppress the unused-variable
  lint) so a due / release field can be added without a
  timezone-offset round-trip.
- **Student viewer / list** — `/student/lessons` + the sidebar
  slot are Task #56; the viewer itself is Tasks #57 + #58. This
  slice only touches the admin / teacher surface.
- **Responsive polish + final i18n parity pass** — Task #59.
  Ad-hoc visual testing on laptop / narrow viewports should be
  reasonable since all the primitives reused here already have
  responsive rules.

How to check as a user:

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100)
   for the frontend.
2. Sign in as a school admin (`admin@demo.local` / `admin12345`)
   or as a teacher assigned to a class. The panel respects the
   same `canMarkAttendance` gate as homework / attendance.
3. Open a class that has at least one enrolled student (e.g.
   the demo class from the #43 / #44 seed steps):
   `/en/admin/classes/<classId>`. Scroll past **Roster**,
   **Schedule**, and **Homework** — the new **Lessons** card is
   the last section above the back link.
4. Happy path — create:
    1. Click **Add lesson**. Fill the title
       ("Lesson 1: The Italian Game"), a short instructions line
       ("Work through each block in order."), optionally pick a
       session from the **Attach to session** dropdown (any
       upcoming session shows up), and submit.
    2. The form closes, the new row appears at the top of the
       list with `Created …`, `0 blocks`, `0 read`,
       `N unread`, and (if a session was picked) a
       **Session · DD MMM HH:mm** pill. The `0 blocks` is
       accurate — no block editor in this slice — and will pick
       up content once Tasks #54 / #55 land.
    3. The instructions line renders below the meta strip with
       ellipsis truncation past ~140 chars; hover to see the
       full text in the `title` tooltip.
5. Happy path — completion sheet:
    1. Click **Show students** on a row. The sheet loads a flat
       list of enrolled students, each with a chip showing
       **Unmarked** (or **Read** / **Not started** if any
       student has already self-marked via the `/me/lessons`
       API). Chips are **read-only** — clicking them does
       nothing.
    2. A student who has marked themselves read shows the
       **Read** chip + a "Marked DD MMM HH:mm" timestamp.
    3. Click **Hide students** to collapse; reopen to confirm
       the detail fetch is cached client-side (no loading
       spinner on re-expand).
6. Happy path — edit:
    1. Click **Edit** on a row. The inline form expands with
       the current title / session prefilled. The instructions
       textarea is **empty by default** — typing in it sends
       the new value, leaving it blank leaves the existing
       instructions alone (same pattern as homework edit; the
       placeholder copy spells this out: "Leave blank to keep
       current instructions").
    2. Change the title, tick **Detach session**, save. The row
       re-renders with the new title and no session pill.
    3. Re-open edit and paste a very long instructions string
       (say 400 chars). Save. The row's instructions preview
       shows the first ~140 chars with an ellipsis; the full
       text is exposed via the hover tooltip.
7. Happy path — archive:
    1. Click **Archive**, accept the native confirm prompt. The
       row disappears from the active list; the header picks up
       a **Show archived (1)** toggle.
    2. Flip the toggle — the archived row reappears with an
       **Archived** pill; Edit / Archive buttons are hidden on
       archived rows; the completion sheet still works.
    3. Flip the toggle back — the active list returns.
8. Edge cases worth eyeballing:
    - **Fresh-lesson archived bug** — create a lesson, verify
      it lands in the Active list (not Archived). Jackson strips
      `archivedAt` from the wire, so the filter uses loose
      nullish equality (`== null`) to route correctly. Same
      gotcha as #25.
    - **Empty state** — on a class with no lessons, the empty
      copy reads "No lessons yet."; the archived tab shows "No
      archived lessons." when empty.
    - **Teacher not assigned** — sign in as a teacher who isn't
      on the class's teacher roster. The panel still renders
      (read-only), but **Add lesson**, **Edit**, and **Archive**
      buttons are hidden; the completion sheet is still
      viewable. The backend enforces the real authorisation — a
      crafted PATCH still returns 403.
    - **Conflict / validation errors** — edit a lesson's title
      to an empty string: submit is blocked by the `required`
      attribute on the input. Submit past the 200-char cap via
      devtools — the action returns `error.invalid` copy under
      the button. Kill the backend (`ctrl-C`) and try to save —
      `error.network` copy appears.
    - **`/ru/admin/classes/...`** / **`/uz/admin/classes/...`** —
      all 50 lesson keys are translated. The blocks-count pill
      shows `0 блоков / 1 блок / 2 блока / 5 блоков` (Russian
      plural forms) and `0 blok / 1 ta blok` (Uzbek).
    - **Block count sanity** — create a lesson via the HTTP API
      with three authored blocks (see the #51 "check as a user"
      body for an example), then refresh the class page. The
      row shows `3 blocks` — proof that the backend's
      `blockCount` denormalisation is flowing through
      end-to-end even before the block editor ships.
    - **Multi-session picker** — add several upcoming sessions
      to the class first. The session dropdown on both the new
      and edit forms shows them all, sorted by the `sessions`
      prop order (which is the upcoming-only / newest-first
      order from #26).

### #52 — Student lesson endpoints + integration test

Third backend slice of the Lessons feature. Mirrors
`MeHomeworkController` / the student paths on `HomeworkService` but
with the lessons-specific semantics baked in: three DTOs, three
service methods (`listForStudent` / `detailForStudent` /
`updateMyCompletion`), and a new `MeLessonController` mounted under
`/api/v1/me/lessons` and gated class-level on `hasRole('STUDENT')`.
Eight-case `StudentLessonFlowIntegrationTest` covers the happy
paths + all the "hide existence" 404 shapes + the 403 gate.
Three deliberate contract divergences from the homework twin:

- **Explicit `NOT_STARTED` is accepted** on the student write
  endpoint — preserves the "opened-but-didn't-finish" trail that
  a null status would erase. The homework equivalent rejects it
  as 400. See the `LessonCompletionStatus` enum docstring for
  the rationale. `markedAt` is refreshed on `NOT_STARTED` writes
  too so the admin sheet can show "last touched at" consistently.
- **Resources are NOT scrubbed on the wire.** An MCQ's
  `correctIndex` + `explanation` and a BOARD_WALKTHROUGH's full
  move list ship to the student verbatim. Lessons are
  revisitable study material; the client mediates reveal UX (a
  UX choice, not a security boundary — the homework equivalent
  strips `solution.uci` because homework is gradable). This is
  documented on both `StudentLessonDetail` and the service
  method javadoc.
- **No parent-of-child surface in v1.** `MeLessonController`
  gates only on `hasRole('STUDENT')` — there's no
  `ParentLessonController` because no parent UI needs it yet.
  Note on the controller flags this as "add here only when a
  parent surface actually needs it."

Other notes:

- **404 hides existence** on all three paths. A student hitting
  `GET /me/lessons/{id}` or `PUT /me/lessons/{id}/completion`
  for a lesson in a class they're not in, an archived lesson,
  or a completely non-existent UUID all return the same 404
  shape. No information leak about the lesson's existence or
  archival state.
- **Rollup batching mirrors homework.** `buildStudentRollup`
  does one roster lookup → one active-lessons batch → one
  class-name batch → one completion batch keyed on
  `(studentUserId, lessonIds)` → one block-count projection
  → one session-start denormalisation. Round-trips stay
  constant regardless of how many lessons the student sees.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/StudentLessonView.java`
  — list-row DTO. Mirrors `StudentHomeworkView` minus `dueAt`,
  `studentNote`, `submittedAt`, `markedBy`, and all the
  `blocks*` counters (lessons have no block-level attempt
  tracking in v1).
- `.../dto/StudentLessonDetail.java` — single-lesson detail
  DTO. Javadoc explicitly calls out that resources are NOT
  scrubbed.
- `.../dto/UpdateMyCompletionRequest.java` — write shape.
  Docstring contrasts explicitly with
  `UpdateMySubmissionRequest` about the `NOT_STARTED` accept.
- `apps/api/src/main/java/com/chessedu/platform/classes/LessonService.java`
  — added three methods in two new sections ("Student
  self-service reads" / "writes") plus private helpers
  `buildStudentRollup`, `toStudentView`, `requireStudent`. All
  three student methods use `AppException.notFound("Lesson")`
  for the not-enrolled / archived / missing paths to hide
  existence.
- `apps/api/src/main/java/com/chessedu/platform/classes/MeLessonController.java`
  — thin REST layer: `GET` (list), `GET /{id}` (detail), `PUT
  /{id}/completion` (upsert). No POST — the `(lesson, student)`
  grain is unique by construction, same as homework.
- `apps/api/src/test/java/com/chessedu/platform/classes/StudentLessonFlowIntegrationTest.java`
  — 8 test methods covering:
  - **Happy paths** — student sees own active lessons only
    (archived + out-of-class filtered); READ + null reset
    round-trip correctly; explicit `NOT_STARTED` is accepted
    (contrasted directly against the homework test); detail
    returns unscrubbed BOARD_WALKTHROUGH moves + MCQ
    `correctIndex`.
  - **404 hide-existence** — marking a lesson in a class the
    student isn't enrolled in; marking an archived lesson;
    detail on both shapes.
  - **Empty list** — fresh student with zero enrolments gets
    `[]`.
  - **403 role gate** — admin token on `/me/lessons` → 403 at
    the `@PreAuthorize` layer.
  Test uses the `Fresh(UUID, email, pw, tag)` per-test user
  pattern from `StudentParentHomeworkFlowIntegrationTest` so
  list-length assertions don't leak state between tests.

Deliberately scoped out:

- **Parent-of-child** endpoint (`/api/v1/parent/children/{id}/lessons`).
  Would be trivial to add — `buildStudentRollup` is already
  keyed on a student UUID, so wrapping it in a parent-link
  permission check is the only work. File as a separate task
  once a parent surface for lessons is actually in the roadmap.
- **Per-block client state persistence** — MCQ guesses,
  board-walkthrough playhead, video playhead all stay on the
  client. The (lesson, student) completion grain is the only
  server-persisted per-student signal. See the
  `MeLessonController` class docstring for why.
- **"Last 10 lessons across classes" feed** — the current
  `listForStudent` returns everything the student has
  outstanding (no pagination). Homework does the same; we'll
  revisit when a student actually accumulates hundreds of
  rows.

How to check as a user:

No UI yet (student viewer is Task #56 / #57 / #58). Verification
is end-to-end via the HTTP API.

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun`. The demo seeder mints
   `student@demo.local` / `student12345` on local profile.
2. Seed prep as admin (`admin@demo.local` / `admin12345`):
   enrol the demo student in a class, then create a lesson
   with a BOARD_WALKTHROUGH + MCQ block (see Task #51's
   check-as-a-user section for a full example body). Grab the
   lesson id as `LESSON_ID` and the class id as `CLASS_ID`.
3. Grab a student token:
   ```bash
   STU=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"student@demo.local","password":"student12345"}' \
     | jq -r .accessToken)
   ```
4. Happy path — list:
   ```bash
   curl -s http://localhost:8080/api/v1/me/lessons \
     -H "authorization: Bearer $STU" \
     | jq '.[] | {id, className, title, status, blockCount}'
   ```
   Expected: one row with `status: null`, `blockCount` matching
   the authored block count. Classes the student isn't enrolled
   in don't appear.
5. Happy path — detail:
   ```bash
   curl -s http://localhost:8080/api/v1/me/lessons/$LESSON_ID \
     -H "authorization: Bearer $STU" | jq
   ```
   Expected: the full lesson including `resources` in position
   order. Crucially, the MCQ block's `payload.correctIndex` is
   present on the wire — contrast with the student homework
   detail, which strips `solution.uci`. The
   BOARD_WALKTHROUGH block's full move list is there too.
6. Happy path — mark READ:
   ```bash
   curl -s -X PUT \
     http://localhost:8080/api/v1/me/lessons/$LESSON_ID/completion \
     -H "authorization: Bearer $STU" \
     -H 'content-type: application/json' \
     -d '{"status":"READ"}' | jq
   ```
   Expected: `status: "READ"` with a non-null `markedAt`. Admin
   list (`GET /admin/classes/$CLASS_ID/lessons`) now shows
   `readCount: 1`.
7. Happy path — explicit NOT_STARTED:
   ```bash
   curl -s -X PUT \
     http://localhost:8080/api/v1/me/lessons/$LESSON_ID/completion \
     -H "authorization: Bearer $STU" \
     -H 'content-type: application/json' \
     -d '{"status":"NOT_STARTED"}' | jq
   ```
   Expected: **200 OK** with `status: "NOT_STARTED"` and a
   refreshed `markedAt`. (The homework twin returns 400 here.)
   Admin `readCount` goes back to 0, but a `LessonCompletion`
   row still exists — confirm by peeking at the admin detail:
   ```bash
   curl -s http://localhost:8080/api/v1/admin/classes/$CLASS_ID/lessons/$LESSON_ID \
     -H "authorization: Bearer $ADMIN" | jq '.completions'
   ```
   You should see the student's row with `status:
   "NOT_STARTED"`.
8. Happy path — reset via null:
   ```bash
   curl -s -X PUT \
     http://localhost:8080/api/v1/me/lessons/$LESSON_ID/completion \
     -H "authorization: Bearer $STU" \
     -H 'content-type: application/json' \
     -d '{"status":null}' | jq
   ```
   Expected: `status` + `markedAt` both omitted from the
   response. Admin detail shows the student's row back to
   `status: null` (deleted and re-materialised as an unmarked
   roster row).
9. Edge cases worth eyeballing:
    - **Class-not-in → 404**: create a second class you
      haven't enrolled the student on. Create a lesson there.
      Call `GET /me/lessons/{that-id}` and
      `PUT /me/lessons/{that-id}/completion` as the student
      → both 404 with the same shape the missing-lesson case
      returns. Nothing about the lesson's existence leaks.
    - **Archived → 404**: archive the lesson via
      `DELETE /admin/classes/$CLASS_ID/lessons/$LESSON_ID`.
      As the student, try to mark it READ → 404. Try to GET
      it → 404. (List also omits it, as the rollup filters
      on `archivedAt IS NULL`.)
    - **Non-student role → 403**: call
      `GET /me/lessons` with the admin token from step 2 →
      403 at the `@PreAuthorize` layer.
    - **Unenrolled student → empty list**: sign in as a
      fresh student with no class memberships (create one
      via the admin `/users` surface if needed). `GET
      /me/lessons` returns `[]`. Same student calling `PUT
      /me/lessons/$LESSON_ID/completion` → 404.
    - **Idempotent READ**: mark READ twice. Both calls return
      200 with the same row, second call just refreshes
      `markedAt`. Admin tally stays at 1.
    - **Response shape parity**: verify list rows and the
      update response share the same
      `StudentLessonView` shape (same keys, same ordering).
      The client can drop either into the same row-renderer
      without a conditional.

### #51 — Admin lesson service + controller + integration test

Second backend slice of the Lessons feature. Stacks the full admin
CRUD surface on top of the #49 persistence layer and the #50
validator: seven DTOs, a `LessonService` that mirrors
`HomeworkService` with the homework-specific fields ripped out, an
`AdminLessonController` mounted under
`/api/v1/admin/classes/{classId}/lessons`, and a 16-case
`LessonFlowIntegrationTest` that exercises every happy path and
every validator rejection path the four block kinds can produce.
Authorisation is identical to homework (SCHOOL_ADMIN / PLATFORM_ADMIN
always, TEACHER only on assigned classes) and the three-state PATCH
semantics on `resources` match (`null`=leave alone, `[]`=clear,
populated=replace). Lessons-specific differences:

- **No `dueAt`** anywhere in the write/read path — lessons are
  revisitable study material.
- **Lighter completion shape**: `LessonCompletionView` is
  `(studentUserId, displayName, status, markedAt)` — no
  `markedBy` / `studentNote` / `submittedAt` because completion
  is self-reported only.
- **`readCount` + `blockCount`** replace homework's
  `doneCount / skippedCount` on the list summary. Block count is
  useful on the list view for surfacing empty / instructions-only
  lessons at a glance.
- **No review / attempt endpoints** — there's no teacher-override
  flow or per-block audit trail. If you want that later you'll add
  it the same way #29-#35 grafted block attempts onto homework.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/LessonResourceInput.java`
  — write shape. Kind is `@Size(max = 32)` so `BOARD_WALKTHROUGH`
  fits (homework's equivalent is 24).
- `.../dto/LessonResourceView.java` — read shape. Docstring notes
  explicitly that nothing is stripped on the wire — MCQ
  `correctIndex` ships to the student by design (the client
  reveals it after the student's guess is locked in; not a
  security boundary).
- `.../dto/LessonCompletionView.java` — lighter than the homework
  equivalent (no `markedBy`, no `studentNote`, no `submittedAt`).
- `.../dto/LessonSummary.java` — trade homework's `dueAt` /
  `doneCount` / `skippedCount` for `readCount` + `blockCount`.
  Retains `sessionStartsAt` denormalisation so the list row can
  show a "Session · 20 Apr 18:00" pill without a second fetch
  (same trick as #26).
- `.../dto/LessonDetail.java` — list of `completions` + ordered
  list of `resources`. No `attempts` field.
- `.../dto/CreateLessonRequest.java` — `title` (required) +
  `instructions` + `sessionId` + `resources` (≤20). No `dueAt`.
- `.../dto/UpdateLessonRequest.java` — all fields optional; adds
  `Boolean clearSession` for the "detach from session" case.
- `apps/api/src/main/java/com/chessedu/platform/classes/LessonService.java`
  — single-service read/write for lessons. `list` batches the roster
  size + completion counts + block counts + session denormalisation
  into three round-trips regardless of row count. `detail`
  builds a roster-ordered completion sheet, appending historical
  rows for students who've since left the roster. `create` saves
  the lesson first (so `persistResources` can FK against it) then
  validates + persists blocks position-by-index. `update` runs the
  three-state PATCH on resources via
  `resources.deleteAllByLessonId(..)` + re-insert. `archive` is
  idempotent and refuses to edit an already-archived row with a
  409. `authoriseClass` duplicates the homework equivalent rather
  than extracting a shared base class — keeps the diff surface of
  this task tight; a future refactor can consolidate the three
  copies (Homework / HomeworkBlockAttempt / Lesson) into one
  helper.
- `apps/api/src/main/java/com/chessedu/platform/classes/AdminLessonController.java`
  — thin REST layer: `GET` (list, `includeArchived` query param),
  `POST` (create), `GET /{id}` (detail), `PATCH /{id}` (update),
  `DELETE /{id}` (archive → 204). Spring Security
  `@PreAuthorize("hasAnyRole('SCHOOL_ADMIN', 'PLATFORM_ADMIN',
  'TEACHER')")` at the class level; the service enforces the
  per-class teacher-assignment check.
- `apps/api/src/test/java/com/chessedu/platform/classes/LessonFlowIntegrationTest.java`
  — 16 test methods covering:
  - **Happy paths** — create with all four kinds and round-trip
    through detail (asserts VIDEO provider derivation: submit
    `"provider":"vimeo"` with a YouTube URL, get `"youtube"`
    back); three-state PATCH (populated/empty/null on
    `resources`); `clearSession` detaches; archive hides from
    default list + 409 on edit; assigned teacher can manage /
    unassigned teacher 403s; cross-class session rejection.
  - **Validator rejection paths** — PROSE with empty markdown;
    BOARD_WALKTHROUGH with illegal FEN; BOARD_WALKTHROUGH with
    an illegal prefix move; BOARD_WALKTHROUGH with an annotation
    whose `ply` is out of range (`moves.length + 1`); MCQ with a
    single option; MCQ with duplicate options (case-insensitive,
    `"Knight"` vs `"KNIGHT"`); MCQ with out-of-range
    `correctIndex`; VIDEO with `http://` scheme; VIDEO on a
    non-allowlisted host (`evil.example`); unknown kind
    (`"QUIZ"`).

Deliberately scoped out:

- **Student endpoints** — `/api/v1/me/lessons` read + completion
  self-mark lives in Task #52. This slice is admin-only.
- **Block-level attempts / history** — no MCQ attempt audit, no
  board-walkthrough progress persistence in v1. The client owns
  all interactive state for lessons; completion is the only
  server-persisted per-student signal.
- **Per-student `LessonCompletion` writes** — students mark
  themselves READ via the student endpoints (Task #52). The admin
  sheet renders what's there but can't mutate it.
- **Paging** — `list` returns everything for a class. Homework
  does the same; we'll revisit when a class actually accumulates
  hundreds of rows.
- **`createdByDisplayName` on summary/detail** — homework doesn't
  carry it either. Add together when the frontend asks.

How to check as a user:

Because there's no UI yet (Tasks #53-#55 light up the author-side
panel), verification is end-to-end via the HTTP API.

1. Start the stack: `docker compose up -d postgres` then
   `./gradlew :api:bootRun`. Wait for the Flyway migration from
   Task #49 to finish and `Started Application` to appear in the
   logs.
2. Grab an admin token (the demo seeder mints
   `admin@demo.local` / `admin12345`):
   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"admin@demo.local","password":"admin12345"}' \
     | jq -r .accessToken)
   ```
3. Pick a class id — any class visible at `/en/admin/classes` in
   the web app works. Grab its UUID from the URL and call it
   `CLASS_ID`.
4. Happy path — create with all four kinds:
   ```bash
   curl -s -X POST \
     http://localhost:8080/api/v1/admin/classes/$CLASS_ID/lessons \
     -H "authorization: Bearer $TOKEN" \
     -H 'content-type: application/json' \
     -d '{
       "title":"Lesson 1: The Italian Game",
       "instructions":"Work through each block in order.",
       "resources":[
         {"kind":"PROSE","payload":{"markdown":"# Italian Game\n\n1.e4 e5 2.Nf3 Nc6 3.Bc4 is the starting move order."}},
         {"kind":"BOARD_WALKTHROUGH","title":"Main line","payload":{
           "startingFen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
           "moves":["e2e4","e7e5","g1f3","b8c6","f1c4"],
           "orientation":"white",
           "annotations":[
             {"ply":0,"text":"Starting position."},
             {"ply":5,"text":"After 3.Bc4 — the Italian Bishop."}
           ]}},
         {"kind":"MCQ","payload":{
           "question":"Which move defines the Italian Game?",
           "options":["3.Bc4","3.Bb5","3.d4","3.Nc3"],
           "correctIndex":0,
           "explanation":"3.Bc4 targets f7, the weakest square in the Black camp."}},
         {"kind":"VIDEO","payload":{
           "url":"https://www.youtube.com/watch?v=demoXYZ",
           "provider":"vimeo"}}
       ]
     }' | jq
   ```
   Expected: 200 with a `resources` array in the order you sent
   (`position` 0…3). Verify the VIDEO block came back with
   `provider: "youtube"` even though the request said `vimeo`
   (the validator derives provider from host, not trusted input).
5. GET the detail back:
   ```bash
   LESSON_ID=<from step 4>
   curl -s http://localhost:8080/api/v1/admin/classes/$CLASS_ID/lessons/$LESSON_ID \
     -H "authorization: Bearer $TOKEN" | jq '{title, resources: [.resources[] | {position, kind}], completions: (.completions|length)}'
   ```
   Expected: four resources in `position` order; `completions`
   equals the roster size (each student gets a row with
   `status: null`).
6. Happy path — list includes the row:
   ```bash
   curl -s "http://localhost:8080/api/v1/admin/classes/$CLASS_ID/lessons" \
     -H "authorization: Bearer $TOKEN" | jq '.[] | {id, title, blockCount, readCount, rosterSize}'
   ```
7. Happy path — PATCH title, replace resources with a single
   PROSE block, detach the session:
   ```bash
   curl -s -X PATCH \
     http://localhost:8080/api/v1/admin/classes/$CLASS_ID/lessons/$LESSON_ID \
     -H "authorization: Bearer $TOKEN" \
     -H 'content-type: application/json' \
     -d '{"title":"Lesson 1 (revised)","clearSession":true,
          "resources":[{"kind":"PROSE","payload":{"markdown":"Short note."}}]}' \
     | jq '{title, sessionId, resources: [.resources[] | .kind]}'
   ```
   Expected: `sessionId: null`, one PROSE block. Re-PATCH with
   `{"resources":[]}` to clear all blocks; re-PATCH with just
   `{"title":"..."}` (no `resources` key) and verify the empty
   block list is preserved (the three-state PATCH doing its job).
8. Happy path — archive:
   ```bash
   curl -i -X DELETE \
     http://localhost:8080/api/v1/admin/classes/$CLASS_ID/lessons/$LESSON_ID \
     -H "authorization: Bearer $TOKEN"
   ```
   Expected: 204 No Content. `GET /lessons` (default `includeArchived=false`)
   no longer returns the row; `GET /lessons?includeArchived=true`
   does, with `archivedAt` populated. PATCH attempts on an
   archived row return 409 CONFLICT.
9. Edge cases worth eyeballing:
    - **PROSE empty**: `{"kind":"PROSE","payload":{"markdown":""}}`
      → 400 `VALIDATION_FAILED`.
    - **BOARD_WALKTHROUGH illegal FEN**:
      `{"startingFen":"not-a-fen","moves":[]}` → 400.
    - **BOARD_WALKTHROUGH illegal prefix move**: a move that
      isn't legal from `startingFen` (e.g. `["e2e5"]` from the
      start) → 400.
    - **BOARD_WALKTHROUGH annotation ply out of range**: with
      `"moves":["e2e4","e7e5"]`, an annotation `{"ply":3}` →
      400 "must be between 0 and 2".
    - **MCQ 1-option**: `{"options":["Only one"]}` → 400.
    - **MCQ duplicate options** (case-insensitive):
      `{"options":["Knight","KNIGHT","Bishop","Rook"]}` → 400.
    - **MCQ bad `correctIndex`**: 2 options + `correctIndex: 2`
      → 400.
    - **VIDEO http scheme**:
      `{"url":"http://youtube.com/watch?v=X"}` → 400.
    - **VIDEO non-allowlisted host**:
      `{"url":"https://evil.example/thing"}` → 400 (current
      allowlist: youtube.com, www.youtube.com, youtu.be,
      vimeo.com, www.vimeo.com, player.vimeo.com).
    - **Unknown kind**: `{"kind":"QUIZ","payload":{}}` → 400.
    - **Cross-class session**: create a lesson with a
      `sessionId` that belongs to a different class → 400 with
      "Session belongs to a different class".
    - **Unassigned teacher**: log in as a teacher who isn't
      assigned to the class; any of the five endpoints returns
      403. An assigned teacher sees the same surface as an
      admin.
    - **Non-existent lesson**: GET / PATCH / DELETE with a
      random UUID → 404 "Lesson not found" (hides existence
      even to admins of the right school — same shape as the
      homework endpoints).

### #50 — Lesson block validators

Server-side validators for the four v1 lesson-block kinds, bundled in
a single `LessonResourceValidator` that mirrors the shape of
`HomeworkResourceValidator` (pure function of
`(kind, payload) -> normalised JsonNode`, throws
`AppException(VALIDATION_FAILED)` on first issue). Like the homework
twin, the validator is what the service calls on every write so the
`kind` column can stay open-ended VARCHAR without the DB caring. The
four kinds:

- **PROSE** — single `markdown` string, ≤50,000 chars. Sanitisation
  of HTML happens client-side at render time (rehype-sanitize); the
  server just enforces size.
- **BOARD_WALKTHROUGH** — `startingFen` + UCI `moves[]` (≤200, each
  legal, verified via the shared `ChessMoves.applyLegalMove`) +
  `orientation` + optional `annotations[]` of
  `{ply: int, text: string}` where `ply` is bounded by
  `[0, moves.length]` inclusive (0 = starting position, `moves.length`
  = the position after the last move). No hidden solution — that's a
  homework-only concept. Cap of 64 annotations × 200 chars each.
- **MCQ** — `question` (≤500 chars) + `options[]` (2–4 non-blank,
  each ≤200 chars, deduplicated by trimmed lowercase content to catch
  authoring mistakes) + `correctIndex` (bounded integer) + optional
  `explanation` (≤2,000 chars).
- **VIDEO** — `url` must parse via `java.net.URI`, scheme must be
  `https`, host must appear in a tight allowlist
  (`youtube.com` / `www.youtube.com` / `youtu.be` / `vimeo.com` /
  `www.vimeo.com` / `player.vimeo.com`). `provider` is derived from
  the host, not trusted from the author's input — a YouTube URL gets
  a `"youtube"` provider even if the form submitted
  `{"provider": "vimeo"}`. Deliberately conservative: we add hosts as
  we need them, not retroactively.

Each validator emits a tidy, canonical `ObjectNode` (only recognised
fields, normalised casing) that the service persists verbatim. This
matches the homework validator's contract, so the replace-on-write
path in the forthcoming `LessonService` can simply loop
`resources.input → validator.validate(...) → resource.setPayload(...)`
with no bespoke massaging per kind.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/chess/LessonResourceValidator.java`
  — single class, split into four `validateXxxPayload` private methods
  behind a public `validate(String, JsonNode)` switch. Reuses the
  private text-helpers pattern from the homework validator (`requireText`
  / `optionalText` / `asText`). Also exposes a
  `normaliseKind(String)` helper so the service can uppercase-trim
  before persisting.
- **No other backend changes.** The validator is a Spring
  `@Component` so `LessonService` will pick it up via constructor
  injection in Task #51. No DTO / entity churn yet — the JPA layer
  from #49 doesn't need to know about the validator.

Coverage: end-to-end via Task #51's integration test. No standalone
unit test file — matches the existing convention
(`HomeworkResourceValidator` has no sibling `Test` file; it's
covered via `HomeworkFlowIntegrationTest`'s validator-rejection
cases). Adding a unit test here would duplicate coverage without
catching anything the service-level test misses.

Deliberately scoped out:

- **Cross-block uniqueness / ordering constraints** — e.g. "MCQ
  blocks should come after their related BOARD_WALKTHROUGH". That's
  a curriculum-design concern, not a validity concern; enforcing it
  server-side would paint us into a corner.
- **Client-side mirror** — each block kind's editor (Tasks #54 /
  #55) will mirror these rules in TypeScript so authors get instant
  feedback, but the backend is the source of truth — a client that
  skips the mirror still hits this validator on write.
- **Markdown parsing server-side** — we don't AST-parse the markdown
  because the only thing we'd do with the parse tree (strip
  dangerous HTML) is cheaper and safer at render time via
  `rehype-sanitize`. Server-side we'd rather let the payload through
  than risk false positives on legitimate markdown edge cases.
- **Per-tenant video host allowlist** — not yet. All tenants share
  the same three providers. If a tenant ever wants to embed a
  private Panopto / Kaltura instance, we'll graft a
  tenant-scoped overlay onto this list.

How to check as a user:

No UI yet — verification runs through the admin create/update
endpoints (which don't exist until Task #51 mounts them). In the
interim, a direct unit exercise is cheap:

1. Once Task #51 lands, create a lesson with one block per kind via
   `POST /api/v1/admin/classes/{classId}/lessons` (see that task's
   check-as-a-user section for a full example). For each kind, verify
   the happy path round-trips and the rejection paths return 400 with
   `VALIDATION_FAILED`:
    - **PROSE**: reject `{"markdown": ""}`, reject 60k chars.
    - **BOARD_WALKTHROUGH**: reject illegal FEN, reject illegal
      prefix move, reject annotation with `ply = moves.length + 1`,
      reject annotation with no `text`, accept happy path with 2-3
      annotations at distinct plies.
    - **MCQ**: reject 1 option, reject 5 options, reject
      `correctIndex = options.length`, reject duplicate options
      (case-insensitive), accept happy path 4-option with
      explanation.
    - **VIDEO**: reject `http://` (not https), reject
      `https://evil.example/thing`, accept
      `https://www.youtube.com/watch?v=XXXX` (provider → `youtube`),
      accept `https://vimeo.com/12345` (provider → `vimeo`).
2. Smoke-check the normalisation: for VIDEO, submit
   `{"url": "HTTPS://WWW.YOUTUBE.COM/watch?v=X", "provider": "vimeo"}`.
   The stored payload should be
   `{"url": "HTTPS://WWW.YOUTUBE.COM/watch?v=X", "provider": "youtube"}`
   — URL preserved (URI's `toString()` doesn't lowercase the scheme),
   provider corrected from the trusted host.

### #49 — Lessons backend foundation: V010 + entities + repos

First slice of the Lessons feature (study-material counterpart to
Homework). Added three new tables via `V010__lesson.sql`:
`lesson` (mirrors homework, minus `due_at`), `lesson_resource`
(mirrors homework_resource with a broader `kind` column — 32 chars
instead of 24 so we can fit `BOARD_WALKTHROUGH`), and
`lesson_completion` (binary `NOT_STARTED` / `READ` state, no
`submittedAt` / `markedBy` / note — lighter than
`homework_submission` because lesson completion is self-reported
and not teacher-markable in v1). JPA entities + Spring Data
repositories match the homework patterns 1-for-1 so the next
slices (validators / service / controller) have the same shape
to grab against.

Files:

- `apps/api/src/main/resources/db/migration/V010__lesson.sql` —
  three tables, per-class `lesson_class_created_idx` +
  `lesson_session_idx` (partial, `WHERE session_id IS NOT NULL`)
  on the lesson table; UNIQUE(lesson_id, position) on resources;
  UNIQUE(lesson_id, student_user_id) on completions plus two
  secondary indexes for admin rollup + student-side read.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/Lesson.java`
  — entity with `classId` / `sessionId` / `title` / `instructions`
  / `createdBy` / `archivedAt` / auditing timestamps. No `dueAt`.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/LessonResource.java`
  — entity with `lessonId` / `position` / `kind` (length 32) /
  `title` / JSONB `payload`. Javadoc spells out the four v1
  payload shapes (PROSE / BOARD_WALKTHROUGH / MCQ / VIDEO).
- `apps/api/src/main/java/com/chessedu/platform/classes/model/LessonCompletion.java`
  — lightweight per-student state. `markedAt` defaults to
  `Instant.now()` at field declaration (same pattern as
  `HomeworkSubmission`).
- `apps/api/src/main/java/com/chessedu/platform/classes/model/LessonCompletionStatus.java`
  — two-value enum. Docstring calls out the parallel to
  `HomeworkSubmissionStatus`.
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/LessonRepository.java`
  — paged finders for the admin list (archived-aware) + a batch
  finder keyed on `List<UUID> classIds` for the student rollup.
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/LessonResourceRepository.java`
  — ordered read + projected `findLessonIdsByLessonIdIn` for
  cheap block counting + bulk delete for replace-on-update.
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/LessonCompletionRepository.java`
  — upsert-by-natural-key finder + batch finders for both
  admin-side and student-side rollups + a reset-delete.

Deliberately scoped out:

- **Validators** — PROSE length / BOARD_WALKTHROUGH moves / MCQ
  structure / VIDEO URL allowlist all live in Task #50. The
  schema is open-ended on `kind` / `payload` so the validator
  owns the invariants.
- **DTOs + service + controller** — Task #51 (admin) + Task #52
  (student) carry those. This slice deliberately stops at
  "the persistence layer exists."
- **No changes to `HomeworkService` / `HomeworkController`** —
  the two entities are sibling concepts that share neither
  state nor authorisation machinery.

How to check as a user:

No UI in this slice — verification is schema-level.

1. Start Postgres: `docker compose up -d postgres` from the repo
   root. Start the backend: `./gradlew :api:bootRun`. Watch the
   Flyway output on boot — you should see
   `Migrating schema "public" to version "10 - lesson"` followed
   by `Successfully applied 1 migration`.
2. Connect to the DB
   (`docker compose exec postgres psql -U chess chess` or
   equivalent) and eyeball the new tables:
   ```sql
   \d lesson
   \d lesson_resource
   \d lesson_completion
   ```
   Expected: `lesson` has no `due_at` column (key differentiator
   from `homework`); `lesson_resource.kind` is VARCHAR(32); both
   indexes on `lesson_completion` are present.
3. Smoke-insert a row (also exercises the DB defaults —
   `gen_random_uuid` + `now()`):
   ```sql
   INSERT INTO lesson (class_id, title)
   SELECT id, 'Schema smoke test' FROM class_group LIMIT 1;
   SELECT id, class_id, title, archived_at FROM lesson
     ORDER BY created_at DESC LIMIT 1;
   ```
   Expected: a single row with `archived_at IS NULL` and a UUID
   id. Roll it back (`DELETE FROM lesson WHERE title = 'Schema smoke test';`)
   before moving on.
4. Verify the CHECK constraints fire as expected:
   ```sql
   INSERT INTO lesson (class_id, title)
   SELECT id, '   ' FROM class_group LIMIT 1;
   ```
   → should fail with `lesson_title_nonblank_chk`.
   ```sql
   INSERT INTO lesson_completion (lesson_id, student_user_id, status)
   VALUES ('<any-uuid>', '<any-uuid>', 'BAD_STATUS');
   ```
   → should fail with `lesson_completion_status_chk`.
5. Confirm Flyway's history table is clean:
   ```sql
   SELECT version, description, success
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
   ```
   Expected: row 10 / `lesson` / `true`.
6. Restart the backend. Expected: no startup errors — Hibernate's
   `validate` ddl-auto (or whatever is configured in
   application-local.yml) should map all three entities cleanly.
   If a column / type mismatch exists between the SQL + the entity,
   the app refuses to start with a clear message pointing at the
   offending field.

### #47 — Rewrite student shell to match homework-v2 mock

Full rewrite of the `/student` shell against
`docs/design/mocks/homework-v2.html` after Bulat's direct note
that the UI was lagging behind the backend. The flat "list card
with date-ribbon rows" from #44 gets replaced by a proper
two-column application chrome: a 220px left sidebar with Kingside
brand tile + Learn / Play nav groupings + user pill pinned to the
bottom, and a main column with a Newsreader-serif page title +
metric subtitle + pill-shaped Active / Finished segmented toggle
above an `.hw-cards` list. Each card is a three-column grid (46px
icon tile / title + meta column / status column) with the title
in Newsreader, a meta strip that carries a blocks count, a clock-
svg deadline pill in one of three tones (default grey / amber
`.tight` when <24h remains / red `.overdue` when past), and the
class name; the status column carries a coloured chip and a 70px
progress bar whose fill colour tracks the chip variant. A
"Mark as finished" micro-action renders underneath any card whose
blocks are all terminal (`REVIEWED_OK` / `NEEDS_WORK` /
`SOLUTION_REVEALED`) so the student can self-mark without
drilling into the detail page. Deliberately scoped out (again):
gamification, coach-name footer, solve-vs-study split, per-home-
work icon fields — none of which exist on the backend DTO yet.

Files:

- `apps/web/src/app/[locale]/student/layout.tsx` — split the
  previous single-page layout into a proper shell: renders
  `<div className="student-shell">` wrapping a `<StudentSidebar>`
  + `<main className="student-main">{children}</main>`. The
  layout now also pre-fetches `/api/v1/me/homework` once so the
  sidebar can render a badge count ("3" next to Homework) without
  every child page re-fetching. The auth gate stays layout-side
  (fall back to `/auth/login` on no access token, hard-logout on
  a non-ok `/auth/me`); the STUDENT role gate stays page-side so
  a future parent-of-student surface can share the same shell.
- `apps/web/src/app/[locale]/student/sidebar.tsx` — new server
  component. Inline SVG knight glyph in the brand tile,
  `initialsFor(me)` monogram ("Asliddin Karimov" → "AK") for the
  avatar, `shortNameFor(me)` for the user pill ("Asliddin K."),
  two `.student-sidebar__group-head` labels ("Learn" / "Play")
  with nav lists underneath. Only **Homework** is a live
  `Link`; Dashboard / Lessons / Puzzles / Classmates /
  Tournaments render as `aria-disabled` span tiles so the chrome
  matches the mock without linking to non-existent routes.
  Hand-off to the `LogoutButton` client island at the bottom.
- `apps/web/src/app/[locale]/student/page.tsx` — rewritten from
  scratch (~510 lines). Derives a dominant status per row via
  `rollUp(hw)` that squashes the five block counters into
  `EMPTY / NOT_STARTED / IN_PROGRESS / SUBMITTED / REVIEWED_OK /
  NEEDS_WORK / REVEALED`. Priority matches the mock: NEEDS_WORK
  always wins (red), then all-REVIEWED_OK (green), then
  all-REVEALED (ghost), then all-SUBMITTED (blue), then any
  attempted (amber IN_PROGRESS), else NOT_STARTED. `chipFor(hw,t)`
  maps to one of six visual variants
  (`neutral / progress / submitted / ok / needs / ghost`) and
  `progressPercentFor(hw)` feeds the matching progress bar.
  `deadlineInfoFor(iso, locale, t)` returns the relative-time
  copy + tone: overdue / tight (<24h) / default with a
  "weekday · N days" flourish in the 1-7 day window.
  `iconForHomework(id)` hashes the UUID into one of six chess
  glyphs so every card gets a stable icon without a backend
  field. `allBlocksTerminal(hw)` gates the "Mark as finished"
  micro-action (rendered as a hidden-input `<form>` bound to
  the existing `finishHomeworkAction`). Active / Finished split
  driven by `?view=finished` query param so the segmented
  toggle is fully server-rendered — no client state. Page
  header's subtitle interpolates assigned / awaiting / next-due
  into one scannable strip (e.g. "3 assigned · 2 awaiting review
  · Next due in 8 h").
- `apps/web/messages/{en,ru,uz}.json` — added two new subtrees
  to the `student.*` namespace: `nav` (9 keys: label, learn,
  play, dashboard, homework, lessons, puzzles, classmates,
  tournaments) and `list` (29 keys: title, tabsLabel, tabActive,
  tabFinished, subAssigned + subAwaiting (plural),
  nextDueSoon / Tomorrow / On, overdue, blocksCount (plural
  with `=0 / =1 / other`), noBlocks, dueToday,
  overdueYesterday, overdueDays, dueInMinutes, dueInHours,
  dueOnWeekday (weekday + plural count), dueOn, chipEmpty,
  chipNotStarted, chipInProgress (done/total), chipSubmitted,
  chipReviewedOk, chipNeedsWork (plural), chipRevealed
  (plural), progressAriaLabel (`{percent}%`), emptyFinished,
  markFinished). Russian uses full `one/few/many/other`, Uzbek
  uses `one/other`. Key parity verified across all three
  locales.
- `apps/web/src/app/globals.css` — heavy surgery. (a) Removed the
  orphan `@media` query at ~2887-2903 that referenced the legacy
  `.student-hw__row-link` / `.student-hw__row-status` selectors
  (only kept the `.student-hw-detail__submitted { margin-left: 0 }`
  reset that was usefully inside it). (b) Removed the entire
  legacy Task #44 list chrome block (`.student-dash*` +
  `.student-hw__*`, ~300 lines) since the page no longer uses
  those class names. (c) Appended ~700 lines of new primitives
  at the tail of the file:
  `.student-shell` (grid `220px 1fr`), full `.student-sidebar*`
  tree (brand tile with SVG sizing, group-head labels, list
  reset, item + item--on (current page) + item--disabled, count
  badge, user pill with avatar + who + logout-btn),
  `.student-main*` (head row, head-copy stack, title 26px
  Newsreader, sub 12.5px, head--detail variant for the
  breadcrumb surface, crumb + crumb-link, meta + meta-item
  + meta-item--due), `.student-tabs*` (pill-shaped segmented
  toggle with --on state matching mock lines 872-875),
  `.hw-cards` list reset, `.hw-card*` (three-col grid, icon
  tile 46×46 radius 8, Newsreader title, meta flex with
  separator bullets, deadline pill with inline clock svg + three
  tone variants, status flex-column, finish micro-action), six
  `.hw-chip--*` variants with matching dot colour, 70px × 4px
  `.hw-progress*` with colour variants tracking the chip,
  `.hw-empty*` dashed-border empty-state, plus responsive
  `@media (max-width: 820px)` collapsing the sidebar to a
  horizontal top bar and `@media (max-width: 640px)` stacking
  the card status column underneath the body, and dark-mode
  polish on chip borders. Brace balance verified via Node
  script: 637 / 637, zero drift.
- `apps/web/src/app/[locale]/student/logout-button.tsx` — no
  behavioural change; re-styled via the new
  `.student-sidebar__logout-btn` class so it reads as a compact
  pill tucked into the user pill rather than a standalone
  button.
- **No changes** to `actions.ts`, `homework/[id]/page.tsx`,
  `homework/[id]/detail-panels.tsx`, or
  `homework/[id]/block-attempt-panel.tsx`. The detail surface
  already got its warm-palette pass in #45 and its chrome slots
  into the new `.student-main` column without JSX churn — the
  breadcrumb, title, meta line, and panels all inherit the
  existing class names, which the CSS pass either kept (detail
  panels) or extended (the `.student-main__head--detail`
  breadcrumb variant).

Deliberately scoped out:

- **Gamification** — XP bar, rank ribbon, streak counter,
  achievements row, rating line chart. Zero backend data powers
  any of these; `StudentHomeworkView` has no counterpart fields
  and the task brief confirmed these are "skip for now" (same
  reasoning as #44). The CSS primitives stubbed in #43
  (`.kit-banner__stat*`, `.kit-banner__progress*`) are still in
  place ready for a future slice.
- **Coach name in the sidebar footer** — the mock shows a
  "Coach · Anna M." line under the user pill. `MeResponse`
  doesn't carry a coach reference, and wiring one up requires
  a backend DTO extension + a "who's your primary teacher"
  lookup that doesn't exist yet. File as a follow-up if the
  classroom-comms surface ever needs it.
- **Solve / study split** — the mock's card meta strip can
  optionally show "12 solve · 8 study" for homework mixing
  puzzle + lesson blocks. `StudentHomeworkView` currently only
  carries an aggregate `blockCount` + per-state counters, not a
  per-block-type breakdown. Would need a dedicated DTO field
  (`solveBlockCount` / `studyBlockCount`) fed from the authored
  resource kinds.
- **Per-homework icon fields** — authors can't pick an icon yet,
  so the six-glyph hash is the best we can do deterministically.
  A future author-side toggle ("Puzzle / Study / Tournament")
  could feed a proper icon column.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100)
   for the frontend. Postgres must be up
   (`docker compose up -d postgres`).
2. Seed prep — sign in as admin (`admin@demo.local` /
   `admin12345`), enrol `student@demo.local` in a class via
   **Add student**, then assign three homework rows with varied
   shapes:
    - One with a due date in the next 2-3 days and 3 board blocks
      (no submissions yet).
    - One due in 10 hours with 1 block and a recorded solution,
      so the amber `.tight` deadline pill is easy to see.
    - One already past due by 2-3 days (edit a homework's due
      date to the past via the admin UI) so the red `.overdue`
      pill renders.
3. Log out, sign in as `student@demo.local`. Land on
   `http://localhost:3100/en/student`.
4. Verify the shell:
    1. The left **220px sidebar** shows the Kingside knight
       brand tile + "Kingside" wordmark at the top.
    2. Under **LEARN** (uppercased, letter-spaced small head),
       **Homework** is the active row with a count badge
       ("3") on the right. Dashboard / Lessons / Puzzles
       render as muted, non-clickable rows.
    3. Under **PLAY**, Classmates and Tournaments are muted
       placeholders.
    4. The user pill at the bottom shows a **circular avatar
       with "DS" monogram** (for "Demo Student"), the
       shortened name, the email underneath, and a **Log out**
       button on the right.
5. Verify the main column header:
    1. Page title **Homework** in Newsreader serif, 26px.
    2. Subtitle strip reads "3 assigned · 0 awaiting review"
       (or with a "Next due in 8 h" tail when a row is within
       24 h).
    3. Segmented toggle on the right with **Active** (on)
       + **Finished** pill, each carrying its count on the
       right if non-zero.
6. Verify the card list:
    1. Three cards stacked vertically, each with a chess-
       piece glyph in a 46×46 warm-paper tile on the left.
    2. The title reads in Newsreader serif; beneath it, the
       meta strip shows **"3 blocks"** / **"1 block"** /
       **"No blocks"**, a bullet separator, the **deadline
       pill with clock icon**, another bullet, then the class
       name.
    3. The row due in >24h shows a grey clock pill
       **"Due Wed · 3 days"**.
    4. The row due in <24h shows an amber-tinted clock pill
       **"Due in 8 h"** with a warmer background and a hint
       of orange-ink text.
    5. The row past due shows a red-tinted clock pill
       **"2 days overdue"** with red ink.
    6. On the right side of each card: the chip
       (**Not started** grey by default; **In progress
       · 2 / 3** amber if the student has started; **2 need
       work** red if the teacher flagged any block; **3 / 3
       reviewed** green once everything is OK) + a thin
       **70px × 4px progress bar** underneath whose fill
       colour matches the chip variant.
7. Click a card — the detail page opens with the same
   sidebar intact and the main column switching to the detail
   chrome (breadcrumb "← Back to homework" + title + meta).
   The body inherits the #45 warm-palette treatment unchanged.
8. Navigate back. Complete all blocks on one homework (via
   the teacher reviewing them OK in a second browser window) —
   the card now shows an **"Mark as finished"** micro-action
   underneath it. Click it → the card disappears from Active
   and reappears under Finished (with a green chip).
9. Click the **Finished** tab. The segmented toggle flips to
   `--on`; the URL becomes `?view=finished`. The newly-
   finished card lives here; the empty-state copy for the
   Active tab would read **"Nothing left to do — time to
   play some chess."** if nothing's left.
10. Edge cases worth eyeballing:
    - **Responsive ≤ 820px** — open DevTools → device
      toolbar → **iPad Mini** (768×1024). The sidebar should
      collapse to a horizontal top strip (brand + nav chips);
      the main column takes the full width. The card grid
      stays three-column but tightens the status column.
    - **Responsive ≤ 640px** — switch to **iPhone 12 Pro**
      (390×844). The card's status column stacks under the
      body column; the chip + progress bar stretch to the
      row width; the "Mark as finished" button fills the
      card.
    - **Dark mode** — toggle via the theme switcher. Verify
      the deadline pill (`.tight` amber and `.overdue` red)
      tints stay legible against the dark paper, the chip
      borders pick up a half-step darker tone so they don't
      wash out, and the progress-bar fills stay saturated.
    - **Zero state** — sign in as a student with no
      homework assigned. The **Homework** title + subtitle
      render with "0 assigned · 0 awaiting review", the
      toggle shows both tabs without counts, and the body
      shows the dashed-border empty card with the "Nothing
      assigned yet." copy.
    - **`/ru/student`** / **`/uz/student`** — Russian pickups
      the plural forms (`1 требует доработки` /
      `2 требуют доработки` / `5 требуют доработки` on the
      needs-work chip) and the weekday abbreviations come
      from the locale ("ЧТ" / "PAY"). The "Mark as finished"
      copy should be fully translated.
    - **Load failure** — kill the API (`ctrl-C` the backend)
      and refresh `/en/student`. The form-error banner
      appears between the header and the empty card, the
      card list is empty, and the chrome (sidebar, title,
      subtitle) stays consistent.

### #43 / #44 / #45 — Student UI redesigned against the Kingside kit

Three-slice visual overhaul of the student shell to match
`docs/design/mocks/kingside-kit.css` + `student.reference.jsx`. #43
swapped the remote Inter stylesheet for `next/font/google` (Inter,
Newsreader, JetBrains Mono — self-hosted, no FOUT, bundled into the
build) and bridged the font variables onto the existing design-tokens
package. #44 rewrote the `/student` list: banner-style greeting with
eyebrow + serif display head + italic deck, and a single assignment
card whose rows adopt the kit's date-block / title-meta / status-column
grid with a dashed underline between rows. #45 repainted the
`/student/homework/[id]` detail + interactive attempt panel with the
warm paper palette, gave the teacher note a left-border scholarly-quote
treatment, and set the interactive board on a paper-sunken backdrop so
it reads as a framed wooden board rather than a floating Canvas.
Gamification bits from the mock (XP, rank, streak, achievements,
rating chart) are deliberately scoped out — no backend data powers
them, and the task brief called them out as "skip for now." The
dashboard banner hero is stubbed in CSS (`.kit-banner*`,
`.student-dash__banner*`) ready for a future slice but not mounted
in JSX yet.

Files:

- `apps/web/src/app/[locale]/layout.tsx` — added `Inter`,
  `Newsreader`, `JetBrains_Mono` from `next/font/google`; each font
  is loaded with `latin` + `latin-ext` subsets, `display: "swap"`,
  and a CSS variable (`--font-inter`, `--font-newsreader`,
  `--font-jetbrains`). The `<html>` element carries all three
  variables in its className so the fonts propagate to every route.
  Removed the old `rsms.me/inter` preconnect + stylesheet link (no
  longer needed — next/font self-hosts). Newsreader loads weights
  400/500/600/700 and both normal + italic styles because the kit's
  display rhythm leans on italic `.kit-deck` + `.student-hw-attempt__
  play-hint`; JetBrains loads 400/500/600 for move notation.
- `apps/web/src/app/globals.css` — large additive pass (~990 lines
  appended between the existing tokens and the kit block).
  Bridge (top of file): a `:root` rule maps the next/font variables
  onto the existing `--font-display` / `--font-body` / `--font-mono`
  tokens so every previously-declared class picks up the right face
  without a rename. Kit primitives section: `.kit-h-display`,
  `.kit-h-title`, `.kit-h-section`, `.kit-eyebrow`, `.kit-deck`,
  `.kit-card` (+ `--flush` variant), `.kit-chip` (+ `--green`,
  `--amber`, `--rust`, `--ink`), `.kit-banner` + `__flag` / `__head`
  / `__body` / `__stat` / `__progress*` (dark scholarly-green panel
  with a diagonal stripe `::before`), `.kit-assign` (3-col grid
  `52px 1fr auto` matching the kit's date-block / title / chip row),
  `.kit-progress` + `--green` variant. Dark-mode overrides where
  the chip / pill tints needed a lower-lightness flip to stay
  legible against the dark paper. `.student-dash` shell + greeting:
  flex-column with `sp-6` gap; greeting flexes horizontally with a
  `--greeting-copy` stack (eyebrow / display / deck) on the left
  and the `--greeting-actions` column on the right. List restyle:
  `.student-hw` is now the kit's elevated-paper card; rows drop
  the old bordered-box look in favour of a dashed-underline grid
  (`52px 1fr auto`) so multiple assignments read as one scannable
  column rather than competing cards. `.student-hw__date` renders
  a small outlined ribbon with `__date-dow` (uppercase weekday,
  mono-scale letterspacing) + `__date-d` (Newsreader numeral).
  `--nodue` variant italicises both lines and drops the weight so
  "— / —" reads as a disabled slot. Status + alert pills repainted
  with the warm palette: `--done` = accent-soft green, `--skipped`
  = rust, `--not_started` = paper-sunken. Alert pills
  (`--needswork` / `--awaiting` / `--revealed`) use matching warm
  tints. Detail page: `.student-hw-detail__breadcrumb` with a hover
  colour swap, `.student-hw-detail__status` card using the same
  kit-card chrome, `.student-hw-detail__instructions` with a 3px
  accent-green left border (scholarly annotation cue), and status
  pills for DONE / SKIPPED / NOT_STARTED matching the list
  palette. Attempt panel: `.student-hw-attempt__title` uses
  Newsreader; `.student-hw-attempt__pill` has five variants
  matching the five display states (not_attempted / submitted /
  reviewed_ok / reviewed_needs_work / solution_revealed) with
  dark-mode counterparts. Teacher note rebuilt as a
  scholarly-quote block: accent-soft background, 3px accent
  green left border, italic Newsreader body. Play hint italic
  serif. `.student-hw-attempt__board` wraps the `<Chessboard>` in
  a paper-sunken pad with a light drop-shadow so the classic wood
  frame reads against the paper surface. Move chips use JetBrains
  mono on paper-elevated. `--reveal` dashed top-border retained.
  Solution panel uses paper-sunken so it reads as nested.
- `apps/web/src/app/[locale]/student/page.tsx` — swapped
  `.dashboard-shell` for `.dashboard-shell student-dash` (keeps the
  inherited max-width + responsive padding tokens, layers the new
  gap / flex rules on top). Replaced the old
  `.dashboard-header`/`.dashboard-title`/`.dashboard-subtitle`
  trio with `.student-dash__greeting` + `.student-dash__greeting-
  copy` carrying a `.kit-eyebrow` label, `.kit-h-display` for the
  "Hi, {name}" title, and a `.kit-deck` for the subtitle. Added a
  `formatDateBlock(iso, locale)` helper that emits the three-letter
  weekday (uppercased) + numeric day-of-month using
  `Intl.DateTimeFormat` locked to Asia/Tashkent. Each row now
  renders a `.student-hw__date` ribbon (with `--nodue` variant
  when `h.dueAt` is null) as the leading grid column. List card
  grew a `.student-hw__head` strip showing the heading alongside
  the total count in mono. Status / alert pills keep their existing
  logic (derived counters) but inherit the new palette from CSS.
  Kept the existing translation keys intact — no i18n changes
  needed for this slice.
- `apps/web/src/app/[locale]/student/homework/[id]/page.tsx` —
  replaced the shared `.dashboard-header` wrapper with
  `.student-dash__greeting`, pulled the breadcrumb up into the
  greeting copy block (above the title) so it reads as "navigate
  up, here's where you are", and swapped `.dashboard-title` for
  `.kit-h-title` (smaller than the list's `.kit-h-display` —
  matches the kit's page-header hierarchy). Not-found fallback
  gets the same greeting shell so the state doesn't jump chrome
  between routes. Meta line is now a
  `.student-hw-detail__meta` paragraph (flex-wrap container) so
  the class / due / assigned chips sit on one line when space
  permits and wrap cleanly on mobile.
- **No changes** to `apps/web/src/app/[locale]/student/homework/
  [id]/detail-panels.tsx` or `.../block-attempt-panel.tsx`. The
  existing classNames (`student-hw-detail__*`, `student-hw-
  attempt__*`) already match the CSS selectors the kit pass
  retrofits, so the restyle applied with zero JSX churn on those
  components. This was deliberate: it preserves commit history
  clarity and limits diff surface to the two pages that actually
  changed shape.

Deliberately scoped out:

- **Gamification** — the mock's XP progress, rank ribbon, streak
  counter, achievements row, and rating line chart are all scoped
  out because no backend fields exist to power them (`MeResponse`
  carries no `xp`, `rank`, or `streak`; `StudentHomeworkView` has
  no rating history). The task brief explicitly confirmed "skip
  them for now". The CSS primitives `.kit-banner__stat*` and
  `.kit-banner__progress*` are in place so a future slice can
  drop a banner in without re-working the stylesheet.
- **Lesson viewer** surface from the mock (`LessonViewer`
  component with MCQ options, annotation panels, "Coach note"
  cards) — not part of the current product yet. The kit chrome
  for those blocks is stubbed in the kingside-kit.css file but
  not ported into globals.css until they get backend data.
- **Achievements row** — same reason as gamification; the mock
  shows three `.achievement` cards but there's no data feed.
- **Homework detail status card** could adopt
  `.kit-chip` variants directly for the DONE / SKIPPED / clear
  action buttons, but they're interactive buttons (`<button>`)
  not passive chips, so keeping them on the existing
  `.btn`/`.btn--primary`/`.btn--ghost` classes is the right call.
  The pill that shows the *current* status already uses
  `.student-hw-detail__status-pill` with kit-matched variants.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up
   (`docker compose up -d postgres`).
2. Seed prep — sign in as admin (`admin@demo.local` /
   `admin12345`), enrol `student@demo.local` in a class via **Add
   student**, then assign two or three homework rows with varied
   dates: one due today, one due next week, one with no due date.
   Give one board block a recorded solution so the attempt flow is
   exercisable end-to-end. Helpful to have a homework where the
   teacher has marked a block Needs work so the red alert pill
   renders.
3. Log out, sign in as `student@demo.local`. Land on
   `http://localhost:3100/en/student`. Verify:
    1. The header reads **HOMEWORK** (eyebrow, uppercase, letter-
       spaced) above **Hi, Demo Student** (Newsreader serif,
       larger) above *"Your homework across every class, newest
       first."* (italic serif). The **Log out** button sits on the
       right edge.
    2. The body shows one card with a small **HOMEWORK** heading
       and a total count in mono (e.g. **3**). Each row has a
       three-letter-weekday / day-of-month ribbon on the left
       (**THU / 23**), a title + meta column in the middle, and
       the status pill stack on the right.
    3. A row with no due date shows the ribbon italicised with
       "— / —" and a "No due date" meta pill.
    4. Hover any row — the background tints to the paper-sunken
       tone and the dashed underline stays visible so the hit
       target is clear.
    5. If any homework has block-level teacher feedback, the
       secondary pills (red "Needs work", amber "Awaiting
       review", indigo "Solution revealed") stack below the
       primary status pill.
4. Click into a homework. Verify the detail page:
    1. Breadcrumb "← Back to homework" sits above the title
       (small, muted) and takes you back on click/keyboard.
    2. The homework title renders in Newsreader serif
       (`.kit-h-title` — smaller than the list's display head).
    3. Meta line shows class / due / assigned pills inline,
       wrapping under narrow viewports.
    4. **Your status** card has a right-aligned submit timestamp
       (mono) when the student has marked something.
    5. If the homework has instructions, they render in a card
       with a 3px green left border (scholarly annotation cue).
    6. Under **BOARD EXERCISES**, each block renders with a
       serif title, a warm-palette status pill (e.g. **Submitted
       — awaiting review** in blue-grey on submit, **Needs work**
       in amber after review), and — when interactive — the
       chessboard sits on a paper-sunken backdrop so it reads as
       framed rather than floating.
5. If the block is in **Needs work** state, verify:
    1. The teacher note renders as an italic Newsreader paragraph
       on an accent-soft green background with a 3px accent-green
       left border (the "scholarly quote" treatment).
    2. The play hint underneath reads in italic serif.
    3. Drag a move on the interactive board — the piece animates
       smoothly, the move appears in **Your moves** as JetBrains
       mono chips on paper-elevated tiles.
    4. The **Show the solution** button sits below the submit row
       on a dashed top border (the "escape hatch" visual).
    5. After revealing, the authored solution section renders in
       a paper-sunken panel with the same stepper chrome.
6. Edge cases worth eyeballing:
    - **Dark mode** — click the theme toggle (or toggle it via
      `document.documentElement.dataset.theme = 'dark'` in the
      console). Verify the banner / chip / alert tints stay
      legible: amber → dark-amber, rust → dark-rust, etc. The
      board-card backdrop switches to a darker paper sunken tone.
    - **Narrow viewport** — open DevTools, toggle device toolbar,
      pick **iPhone 12 Pro** (390×844). The greeting stacks the
      copy + logout vertically when the flex row wraps. List
      rows drop the status column to a new line (grid
      re-templates to `52px 1fr` with the status row spanning
      both columns). Detail page boards grid drops to a single
      column. Attempt panel buttons stack full-width.
    - **`/ru/student`** / **`/uz/student`** — Russian + Uzbek
      copy should pick up Newsreader / Inter just like English.
      The date ribbon's weekday uses the locale's abbreviation
      ("ЧТ" / "PAY"), not the English one.
    - **Empty state** — if the student has no homework, the card
      still renders the **HOMEWORK** heading (no count pill) and
      an italicised *"Nothing assigned yet. Check back after
      your next class."* on a dashed separator.
    - **Not-found homework** — visit
      `/en/student/homework/<random-uuid>`. You should land on
      the same `.student-dash` greeting shell with the
      breadcrumb + italic deck copy — no chrome jump.
    - **Load failure** — kill the API (`ctrl-C` the backend) and
      refresh `/en/student`. The form-error banner appears
      between the greeting and the list card, the list is
      empty, and the chrome stays consistent. Restart the API
      and refresh.

### #41 — Responsive pass for student board + admin review

Pure-CSS follow-up for the #38–#40 surfaces. Walks the student
interactive board, the revealed-solution card, the student list
row, and the admin review surface through the 640 / 560 / 420px
breakpoints so classroom tablets and phones render the flows
without overflow, cramped touch targets, or iOS focus zoom. No
component refactors — `react-chessboard` v5 already ships touch
sensors via dnd-kit so drag-drop on phones works out of the box;
the only change the board itself needed was the outer grid min
width so it doesn't force horizontal scroll at 375px.

Files:

- `apps/web/src/app/globals.css`:
    - `.student-hw-detail__boards-list` — lowered `minmax(320px, 1fr)`
      to `minmax(min(100%, 280px), 1fr)` and added a single-column
      fallback under 560px. The `min(100%, 280px)` trick keeps the
      card usable at 280–320px without forcing a grid overflow on
      narrow phones.
    - `.bcv__move` — bumped padding to `6px 10px` with a
      `min-height: 32px` floor so the step-through move chips clear
      the WCAG 2.5.5 target-size rule on touch. Desktop read is
      unchanged — the chips still sit tidily in a wrap-row.
    - `.bcv__solution-head` — added `flex-wrap: wrap` so the "Show
      / hide solution" button line can wrap rather than getting
      squeezed next to its heading on narrow screens.
    - `.student-hw-attempt__moves-head` — stacks label + count
      vertically at ≤640px (they previously fought for horizontal
      space with `justify-content: space-between`).
    - `.student-hw-attempt__actions` + `.student-hw-attempt__reveal`
      — new ≤420px rule flips the three-button action row to a
      column with each button stretched to full card width. Same
      treatment for the "Show solution" escape hatch.
    - `.student-hw__row-status` — on ≤640px where the list row
      stacks, flip the pills' flex `align-items` from `flex-end` to
      `flex-start` so the primary status + alert pills line up with
      the row title's left edge rather than dangling right-aligned
      in a narrow column.
    - `.hw-review-card__note-input` — explicit `font-size: 16px`
      kills iOS Safari's auto-zoom on focus; also added the padding
      / border / surface colours the textarea was missing (it was
      inheriting `.sched-field__input` chrome via a non-existent
      class, so it had been rendering as an unstyled textarea).
    - `.hw-review-card__actions > .btn` — full-width on ≤640px so
      the stacked Mark OK / Mark needs work buttons are equally
      sized (`align-items: stretch` alone only stretches the
      cross-axis; buttons still took intrinsic width).
    - `.hw-review-card` — trimmed padding from 14px to 10px under
      640px to give the nested `BoardCardView` a touch more room
      on narrow phones.

Deliberately scoped out:

- **`touch-action` on the interactive board container** — dnd-kit
  sets this on the draggable pieces themselves. Forcing
  `touch-action: none` on the whole board would block page scroll
  while the student's finger is inside the board, which can feel
  trapped on landscape-orientation phones where the board fills
  most of the viewport. Revisit if real-world testing shows
  gesture conflicts.
- **History / audit modal** — the admin review card doesn't yet
  link out to a per-block history view. When that lands it'll get
  its own responsive treatment.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up (`docker compose up -d postgres`).
2. Seed a homework with two BOARD blocks as admin
   (`admin@demo.local`), enrolling `student@demo.local`. Give one
   block a recorded solution so the reveal path is exercisable.
3. Student path — open `http://localhost:3100/en/student/homework/<id>`
   in Chrome / Safari. Open DevTools → Toggle device toolbar →
   select **iPhone 12 Pro** (390×844) and **iPhone SE** (375×667).
   Verify:
    1. The boards grid drops to a single column; the interactive
       board fills the card width without overflowing horizontally.
    2. Drag a piece with touch emulation on. The move registers
       and appears in the moves list; the page doesn't scroll
       awkwardly during the drag.
    3. The **Undo / Reset / Submit** buttons wrap; at ≤420px they
       stack and stretch to full width.
    4. After marking Needs work (admin path below), the **Show the
       solution** button sits full-width under the dashed separator.
    5. Revealed solution panel renders below; step-through buttons
       (`◀ ▶ ⏮ ⏭`) + move-list chips are comfortably tap-sized
       (try stepping through the line with your thumb).
4. Admin path — same device toolbar. Open the class → Homework →
   **Submissions**. Verify:
    1. Under **Block attempts**, each review card stacks its head
       (student meta + pill) vertically; the read-only board fits
       the card; teacher-note textarea is full-width with a visible
       border (before the fix it had no chrome and collapsed to
       basically invisible on dark mode).
    2. Tap the textarea on a real iOS device (or the simulator) —
       the viewport should NOT zoom in on focus. Previously it did
       because the browser-default textarea font is ~13.3px.
    3. **Mark OK / Mark needs work** buttons stack and fill the
       card width at ≤640px.
5. List-row stacking — narrow to ≤640px on `/en/student`. The
   homework row's status + alert pills should left-align with the
   title / meta above them rather than staying right-aligned in a
   skinny column.
6. Edge cases worth eyeballing:
    - Rotate the simulated phone to landscape — the single-column
      boards grid stays at one column (because min-width is still
      the effective constraint up to 560px); on larger devices
      the two-column layout returns.
    - Switch locale to `/ru` or `/uz` — pill / button copy can be
      ~30% longer in Russian; verify the Mark OK / Mark needs
      work stretching doesn't truncate, and the reveal copy
      ("Показать решение") still reads cleanly full-width.
    - Zoom desktop Chrome to 200% to simulate a low-DPI mobile;
      interactions should stay usable even when the boards-list
      grid falls back to single-column at the narrowest tier.

### #42 — Student list row: surface block-level NEEDS_WORK state

Bug fix for: "students page do not show the task is in NEED WORK
state." The `/student` homework list was only rendering the
homework-level self-mark (NOT_STARTED / DONE / SKIPPED), so a
teacher's block-level **Needs work** review was invisible until the
student drilled into the detail page. Extended
`StudentHomeworkView` with five block-level counters
(`blockCount`, `blocksAwaitingReview`, `blocksReviewedOk`,
`blocksNeedsWork`, `blocksSolutionRevealed`) so the list row can
surface up to three secondary pills beside the primary status:
red **Needs work** (highest priority), indigo **Solution
revealed**, and amber **Awaiting review**. Block-level state is
orthogonal to the homework-level self-mark — a student can have
self-marked a homework DONE while still having unaddressed
teacher feedback, so both show side-by-side rather than the
block state overriding the pill.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/StudentHomeworkView.java`
  — appended five trailing `int` fields with docstrings explaining
  the rollup semantics and the orthogonality to the homework-level
  status. Derived `SOLUTION_REVEALED` is broken out as a separate
  counter because the student UI treats it differently from
  vanilla `REVIEWED_NEEDS_WORK` ("seen the solution, retry in
  progress" vs. "unresolved feedback").
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/HomeworkResourceRepository.java`
  — added `findHomeworkIdsByHomeworkIdIn(List<UUID>)` + imported
  `@Param`. Returns a flat list of homework IDs (one row per
  block) so the service can tally per-homework block counts
  in-memory without fetching JSONB payloads.
- `apps/api/src/main/java/com/chessedu/platform/classes/HomeworkService.java`
  — rewrote `buildStudentRollup` to batch-load (1) block counts via
  the new repository method and (2) the caller's live block
  attempts via the pre-existing
  `blockAttempts.findCurrentForStudentAcrossHomework(studentUserId,
  hwIds)` finder (whose docstring already announced this use case
  but was unused until now). Tallies are accumulated into a
  `Map<UUID, int[]>` with a 4-slot array (`t[0]=awaitingReview`,
  `t[1]=reviewedOk`, `t[2]=needsWork`, `t[3]=solutionRevealed`) so
  there's no extra allocation per homework. Widened `toStudentView`
  from 3 to 8 args to pass the counts through. Also fixed the
  single-row return path in `updateMySubmission` — that call site
  now re-runs the tally narrowly for one homework using
  `findCurrentForStudent(homeworkId, studentUserId)` so the DTO
  shape stays consistent across list and single-row endpoints.
- `apps/web/src/app/[locale]/student/page.tsx` — extended the
  TypeScript `StudentHomeworkView` type with the five new fields
  (JSDoc mirrors the Java docstring so the next agent has context
  without bouncing across languages). Row render now emits up to
  three `<span class="student-hw__alert student-hw__alert--...">`
  pills inside `.student-hw__row-status`, stacked vertically below
  the primary status pill. Order of emission is
  needswork → revealed → awaiting so the most-urgent state wins
  the top slot.
- `apps/web/messages/{en,ru,uz}.json` — added three plural-aware
  keys under `student.row.*`: `blocksNeedsWork`,
  `blocksAwaitingReview`, `blocksSolutionRevealed`. Russian uses
  `one / few / many / other`; Uzbek uses `one / other`. Key parity
  verified: en=506, ru=506, uz=506.
- `apps/web/src/app/globals.css` — switched
  `.student-hw__row-status` from a plain block to a right-aligned
  flex column with 6px gaps so the stacked pills land tidily.
  Added `.student-hw__alert` base style and three variant colours
  (`--needswork` = red, `--awaiting` = amber, `--revealed` =
  indigo) that use the same rounded-pill chrome as the primary
  status for visual cohesion.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up (`docker compose up -d postgres`).
2. Sign in as admin (`admin@demo.local` / `admin12345`). Open a
   class with the demo student enrolled (`student@demo.local`),
   assign a homework with two BOARD blocks. Each block should
   carry a recorded solution (e.g. starting FEN = default,
   solution = `e2e4`).
3. Log out, sign in as `student@demo.local`. Land on `/en/student`.
   The row should read **Not started** with no secondary pills —
   nothing has happened yet.
4. Open the homework, drag a wrong move on block 0 (e.g. `d2d4`),
   click **Submit**. Drag another wrong move on block 1, submit.
   Navigate back via the breadcrumb. The row now shows
   **Not started** plus an amber **2 awaiting review** pill
   beneath it.
5. Log out, sign in as admin. Open the class → **Homework** →
   **Submissions** on the row. Mark block 0 as **Needs work**.
   Mark block 1 as **Marked OK**. Log out.
6. Log back in as the student. `/en/student` should now show
   **Not started** plus a red **1 needs work** pill. The
   awaiting-review pill is gone because the teacher addressed
   both (one OK, one NEEDS_WORK).
7. Drill in, click **Show the solution** on block 0, then
   navigate back. The row now shows **Not started** plus an
   indigo **1 solution revealed** pill. (The underlying row is
   still `REVIEWED_NEEDS_WORK` — revealing the solution derives
   the `SOLUTION_REVEALED` display state, counted separately.)
8. Reproduce the solution on block 0's interactive board, click
   **Submit**. Navigate back. The row now shows **Not started**
   plus an amber **1 awaiting review** pill (block 0 flipped back
   to `SUBMITTED` on resubmit, block 1 is still `REVIEWED_OK`
   which doesn't render a pill).
9. Click **Mark as done** on the detail page. Navigate back. The
   row now reads **Done** (primary pill) plus **1 awaiting
   review** (secondary). Confirms the homework-level self-mark
   and the block-level teacher state are orthogonal.
10. Edge cases worth eyeballing:
    - A homework with zero blocks (just a title + instructions)
      should render the same as before — all five new counters
      are 0, no secondary pills shown.
    - A homework on a class the student is enrolled in but no
      blocks authored yet → same clean row, no secondary pills.
    - Open `/ru/student` or `/uz/student` — the three new plural
      pills should be fully translated. Russian uses four plural
      forms (`one/few/many/other`); verify "1 требует доработки"
      vs. "2 требуют доработки" vs. "5 требуют доработки"
      render correctly by assigning homework with 1, 2, and 5
      blocks and marking each Needs work.
    - Teacher marks all blocks OK → no secondary pills (the
      `blocksReviewedOk` counter exists on the DTO for future
      use but isn't rendered; "OK on every block" is already
      implicit in the absence of alerts).
    - The backend's `updateMySubmission` response also carries
      the new fields, so any client that hits the self-mark
      endpoint and re-renders the row inline will see the same
      rollup. Check via curl:
      ```bash
      curl -X PUT \
        http://localhost:8080/api/v1/me/homework/$HW/status \
        -H "authorization: Bearer $STU" \
        -H 'content-type: application/json' \
        -d '{"status":"DONE"}' | jq .blocksNeedsWork
      ```
      Expected: the live count (0 if no teacher feedback, >0
      otherwise).

### #39 — Student revisit + reveal UX after Needs-work

Third frontend slice of Task #28. When a teacher marks a block
attempt `REVIEWED_NEEDS_WORK`, the student now gets a **Show the
solution** escape hatch inline with the submit actions. Clicking it
POSTs to `/api/v1/me/homework/{id}/blocks/{blockIndex}/reveal-solution`
through the new `revealSolutionAction`, which `revalidatePath`s the
student detail so the backend's un-stripped `solution.uci` flows
back on the next fetch. The panel then renders a dedicated
**Solution** section below the interactive board — a second
`BoardCardView` instance whose stepping-start is the post-prefix
FEN, so the stepper walks only the authored continuation. The
derived `SOLUTION_REVEALED` status also stays interactive so the
student can reproduce the line and resubmit in one motion; on a
successful resubmit the backend flips the row back to `SUBMITTED`,
the solution gets stripped on next fetch, and the panel disappears
(closing the loop). Teacher note is now also preserved through
the `SOLUTION_REVEALED` state.

Files:

- `apps/web/src/app/[locale]/student/actions.ts` — added
  `RevealSolutionResult` discriminated union and
  `revealSolutionAction(homeworkId, blockIndex, locale)` server
  action. POSTs to the existing reveal endpoint, maps 401 / 403 /
  404 / 409 / 400-422 / network into the existing error union, and
  `revalidatePath(/{locale}/student/homework/{id})` on success. The
  doc comment explicitly notes that 409 covers both "no live
  NEEDS_WORK attempt" and "no authored solution" — both map to
  `"conflict"` on the wire because the backend doesn't distinguish
  them, and the UI copy reads "Solution isn't available for this
  block" which covers both cases.
- `apps/web/src/app/[locale]/student/homework/[id]/block-attempt-panel.tsx`
  — extended `BlockAttemptLabels` with three new fields
  (`solutionRevealedInstruction`, `showSolution`, `revealing`).
  Added `useRouter` import, `revealError` state, a separate
  `startRevealTransition` so reveal + submit don't share a pending
  flag, and the new `onShowSolutionClick` handler that calls the
  action + `router.refresh()`. Added `onRevealed`
  `(attempt: BlockAttemptView) => void` prop so the parent can
  stash the fresh row. Updated `interactive` to include
  `SOLUTION_REVEALED` so the retry board stays live. New
  `solutionBlock` memo builds a synthetic `BoardCardBlock` with
  `startingFen = baseFen`, `moves = block.solution`, `solution =
  null` — handed to `BoardCardView` with `canRevealSolution={false}`
  to reuse its stepper for the revealed continuation. Play-hint
  copy now switches three ways (`SOLUTION_REVEALED` →
  `solutionRevealedInstruction`, `REVIEWED_NEEDS_WORK` →
  `retryInstruction`, else → `playInstruction`). "Show solution"
  button renders inside the actions column when `status ===
  "REVIEWED_NEEDS_WORK" && !hasSolution`. Teacher-note gate
  widened from `REVIEWED_NEEDS_WORK` only to
  `(REVIEWED_NEEDS_WORK || SOLUTION_REVEALED)` so feedback stays
  visible next to the authored solution.
- `apps/web/src/app/[locale]/student/homework/[id]/detail-panels.tsx`
  — added `solutionRevealedInstruction` / `showSolution` /
  `revealing` to `buildAttemptLabels(t)`. Added `onAttemptRevealed`
  callback (same replace-in-place shape as `onAttemptSubmitted`)
  so the local `liveAttempts` mirror picks up the
  `solutionRevealedAt` stamp without waiting for
  `revalidatePath`. Wired through to `<BlockAttemptPanel>` as
  `onRevealed`.
- `apps/web/messages/{en,ru,uz}.json` — added three new keys to
  the `student.attempt.*` subtree:
  `solutionRevealedInstruction`, `showSolution`, `revealing`. Key
  parity verified across all three locales (en: 503, ru: 503,
  uz: 503).
- `apps/web/src/app/globals.css` — appended
  `.student-hw-attempt__reveal` (column layout with a dashed
  top border so the button reads as a distinct "escape hatch"
  below the submit row) and `.student-hw-attempt__solution` +
  `.student-hw-attempt__solution-heading` (section chrome around
  the reused `BoardCardView`). Narrow-viewport rule tightens the
  solution panel padding.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up (`docker compose up -d postgres`).
2. Sign in as admin (`admin@demo.local` / `admin12345`). Open a class
   with the demo student enrolled, assign a new homework with one
   BOARD block that carries a recorded solution (e.g. starting FEN
   = default, solution = `e2e4`). Open the Submissions sheet — no
   attempts yet.
3. Log out, sign in as `student@demo.local`. Open the homework,
   drag a wrong move on the block (e.g. `d2d4`), click **Submit**.
   Pill flips to **Submitted**.
4. Back to admin. Open the Submissions sheet → the new **Block
   attempts** section shows the student's attempt awaiting review.
   Type a short note ("Check e-pawn push first"), click **Mark
   needs work**. The pill flips to **Needs work**.
5. Back to student — refresh the homework detail. The block now
   shows:
    1. The **Needs work** pill.
    2. The teacher's note in a highlighted box.
    3. An interactive board reset to the starting position.
    4. A **retry** play hint.
    5. Under the submit row, a new **Show the solution** button.
6. Happy path — reveal:
    1. Click **Show the solution**. Button briefly reads
       **Revealing…**, then disappears.
    2. The pill flips to **Solution revealed**. The play hint now
       reads "Walk through the authored solution below, then replay
       it on the board and resubmit." The teacher's note stays
       visible.
    3. A new **Solution** section appears below the interactive
       board, rendered through the shared `BoardCardView`. Step
       through the authored move (`e2e4`) with ▶ / clickable move
       list. The board in the solution section animates
       independently of the interactive board above.
7. Happy path — reproduce + resubmit:
    1. On the interactive board (above), drag `e2 → e4`. The move
       appears in **Your moves** as `e2e4`.
    2. Click **Submit attempt**. The pill flips to **Submitted —
       awaiting review**. The board becomes read-only (status is
       no longer interactive). The solution section disappears
       (backend stripped it back off the wire because the live row
       is now SUBMITTED, not REVIEWED_NEEDS_WORK with a reveal
       stamp).
8. Edge cases worth eyeballing:
    - **409 — reveal before review**: skip the admin review step.
      Click **Show the solution** on a **Submitted** block — the
      button isn't rendered at all (gate is
      `status === "REVIEWED_NEEDS_WORK"`), so this can only be
      triggered via crafted POST. The backend returns 409, which
      the action maps to `"conflict"` → "Your status changed
      elsewhere. Refresh and try again." copy under the button.
    - **409 — no authored solution**: create a second homework
      block with NO `solution.uci`. Submit, review as Needs work,
      click **Show the solution** on the student side. The button
      click fires, the server returns 409 (same reason —
      indistinguishable on the wire from the above case), the
      inline error appears. Decision: accept the ambiguity; the
      student's next action is the same either way (wait / ask).
    - **Idempotent reveal**: click **Show the solution** twice in
      rapid succession. The `revealing` gate drops the second
      click; if you DO manage to fire it (via devtools), the
      server returns the same row with the same
      `solutionRevealedAt` stamp. No side-effects.
    - **Resubmit path closes the loop**: after reveal, click
      **Submit attempt** with any sequence. The pill flips to
      **Submitted**, the interactive board goes read-only, the
      solution section vanishes. If the teacher reviews as Needs
      work AGAIN, the student has to click **Show the solution**
      again to re-reveal — intentional. (A reveal counter is a
      nice-to-have, not in scope.)
    - **Teacher note persistence**: the original teacher note from
      step 5.b stays visible through SOLUTION_REVEALED, because
      the row underneath is still the same live row with a reveal
      stamp. After resubmit (step 7) the note disappears because
      the new row has no teacher note yet.
    - Switch locale to `/ru/student/...` or `/uz/student/...` —
      the three new strings (solution-revealed hint, Show the
      solution / Revealing…) should all be translated. The
      **Solution** section heading reuses `viewer.solutionHeading`
      so it stays in sync with the admin preview's label.

### #40 — Admin review surface on Submissions sheet

Second frontend slice of Task #28. The admin Submissions sheet now
hosts a **Block attempts** section grouping every student's live
attempt by authored block. Each row renders a read-only replay of
the student's moves (post-prefix FEN baked into the starting
position, same `BoardCardView` the student sees) next to a teacher
note textarea and **Mark OK / Mark needs work** buttons that POST
through the existing `/api/v1/admin/classes/{classId}/homework/{id}/attempts/{attemptId}/review`
endpoint. Attempts are sorted oldest-first within each block. A 409
from the backend (student resubmitted between the sheet opening and
the button click) shows an inline stale notice instead of a generic
error. Optimistic local `liveAttempts` state in `SubmissionSheet`
splices the fresh row in so the pill / note updates paint instantly;
a `router.refresh()` runs in a transition so the class page's tallies
stay honest.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/homework-actions.ts`
  — added `BlockAttemptView` type (mirrors backend DTO) and
  `attempts: BlockAttemptView[]` on `HomeworkDetail`. Added
  `ReviewBlockAttemptResult` discriminated union and
  `reviewBlockAttemptAction(classId, homeworkId, attemptId, status,
  teacherNote, locale)` server action. POSTs to
  `hwUrl(classId, /${homeworkId}/attempts/${attemptId}/review)`, maps
  409 as `"conflict"` so the UI can show the stale notice, and
  `revalidateDetail(locale, classId)` on success.
- `apps/web/src/app/[locale]/admin/classes/[id]/block-review-panel.tsx`
  — new client component (~340 lines). Exports `BlockReviewSection`
  + `BlockReviewLabels`. `displayStatusFor(attempt)` mirrors the
  student side — a live `REVIEWED_NEEDS_WORK` row with a non-null
  `solutionRevealedAt` becomes the derived `SOLUTION_REVEALED`
  display state (visual distinction; teachers can still flip it back
  to OK). `computeBaseFen(block)` plays the authored prefix on top of
  `startingFen` via chess.js; the replay's synthetic block uses that
  as its `startingFen` with `moves = attempt.moves` and `solution =
  null` so the stepper only walks the student's work. Grouping: one
  `useMemo` builds `Map<number, BlockAttemptView[]>` keyed by
  `blockIndex`, each list sorted oldest-first by `createdAt`.
  Blocks prop is a dense `(BoardCardBlock | null)[]` indexed by
  authored position so `attempt.blockIndex` matches the array index
  even if a resource failed to parse.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — wired `BlockReviewSection` into `SubmissionSheet`. Added
  `blockReviewLabels(t)` helper (same label-bag pattern as
  `boardViewerLabels`, interpolation keys exposed as functions to
  keep next-intl happy), `liveAttempts` local state (seeded on detail
  fetch), and `handleAttemptReviewed(fresh)` that
  replaces-in-place by attempt id and kicks a `router.refresh()`
  inside a transition. Mounts the section between the existing board
  preview and the per-student chip list, gated on `canEdit &&
  reviewBlocks.length > 0`. Imports `parseBoardBlock` +
  `BoardCardBlock` from the viewer module so the dense
  position-indexed blocks array can be built without re-parsing.
- `apps/web/messages/{en,ru,uz}.json` — added the
  `admin.classes.homework.sheet.review.*` subtree (15 keys covering
  section heading, empty state, block heading, submitted / reviewed
  meta lines, four status labels, teacher note label / placeholder,
  two button labels + saving copy, and the stale notice). Key parity
  verified: en=500, ru=500, uz=500.
- `apps/web/src/app/globals.css` — appended the `.hw-sheet__review`
  block and `.hw-review-card*` styles (card, head, student / reviewed
  meta, pill with 4 variants, note textarea, actions row, error, plus
  a narrow-viewport override that stacks the head + actions).

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up (`docker compose up -d postgres`).
2. Sign in as admin (`admin@demo.local` / `admin12345`). Open a class
   that has the demo student enrolled; if you don't have one yet,
   enrol `student@demo.local` via **Add student** on the class's
   roster.
3. Assign a homework with at least one BOARD block (e.g. "Play 1.e4",
   starting FEN = default). Record a solution on the block if you
   want to exercise the `SOLUTION_REVEALED` pill later.
4. Log out, sign in as `student@demo.local`. Land on `/en/student`,
   open the homework, drag a move on the first block (e.g. `e2e4`),
   click **Submit**. The row flips to **Submitted**.
5. Log back in as admin. Open the same class → **Homework** →
   click **Submissions** on the row you assigned. Under the existing
   "Chess content preview" section, a new **Block attempts** section
   now lists the student's attempt card-per-block:
    1. The card shows the student's display name, "Submitted &lt;when&gt;",
       an **Awaiting review** pill, a read-only replay board you can
       step through (only the student's moves — the authored prefix
       is baked into the starting position), a **Note to student**
       textarea, and **Mark OK / Mark needs work** buttons.
    2. Click **Mark OK** — the pill flips to **Marked OK** and a
       "Reviewed &lt;when&gt;" line appears on the meta row. The OK
       button is now the primary / pressed state.
    3. Type a short note ("Try harder, please check knight moves"),
       click **Mark needs work** — pill flips to **Needs work**, the
       note saves on the attempt.
6. Optional — exercise the **SOLUTION_REVEALED** derived state:
    1. Log back in as the student. The block should show a revealed-
       teacher-note box. Reveal the authored solution (see #28
       umbrella — there's currently no UI so use the
       `/api/v1/me/homework/{id}/blocks/0/reveal-solution` POST; a
       dedicated reveal button is Task #39).
    2. Refresh the admin sheet — the same card's pill is now
       **Solution revealed** (the teacher can still re-review it).
7. Edge cases worth eyeballing:
    - Open two admin tabs on the same sheet. In tab A click **Mark OK**.
      In tab B, without refreshing, click **Mark needs work** on the
      same card. The second click should now succeed (the row is
      still live — status flipping doesn't replace it). To exercise
      the 409 path, switch back to the student account between the
      two admin actions and resubmit the block (which replaces the
      live row with a new one). The now-stale card in tab B shows
      the inline **stale notice** copy instead of the generic error.
    - A block with no submissions (e.g. add a second block to the
      homework, leave the student's submission on block 0 only)
      renders with its heading and a muted **No attempts yet.**
      line — the block stays visible so the teacher can see it's
      authored.
    - A teacher who isn't assigned to the class sees the
      `canEdit=false` path: the review section isn't rendered at all
      (the existing per-student chip list also goes read-only). The
      backend enforces the real authorisation, so even a crafted
      POST returns 403.
    - Switch locale to `/ru/admin/classes/...` or
      `/uz/admin/classes/...` — section heading, block heading,
      submitted / reviewed meta lines, four status pills, the
      teacher-note placeholder + label, both buttons, saving copy,
      and the stale notice should all be fully translated.

### #38 — Student interactive board: drag moves + submit attempt

First frontend slice of Task #28 (student-submitted solution moves).
The student homework detail page now replaces the read-only
`BoardCardView` for un-attempted / needs-work blocks with an
interactive board: the student drags pieces on the post-prefix
position, accumulates a local UCI draft, and submits via the existing
`POST /api/v1/me/homework/{id}/blocks/{blockIndex}/attempts` endpoint.
For `SUBMITTED` / `REVIEWED_OK` / `SOLUTION_REVEALED` blocks the panel
falls back to a read-only replay of the attempt's moves. Optimistic
local state (`liveAttempts` in the parent) updates the pill + teacher
note without waiting for the server revalidation round-trip. Reveal +
try-again UX is intentionally scoped out — that's #39.

Files:

- `apps/web/src/app/[locale]/student/actions.ts` — added
  `BlockAttemptView` type (mirrors backend DTO),
  `SubmitBlockAttemptResult` discriminated union, and the
  `submitBlockAttemptAction(homeworkId, blockIndex, moves, locale)`
  server action. POSTs to the existing attempts endpoint, maps 401 /
  403 / 404 / 409 / 400-422 / network into the existing error union,
  and `revalidatePath(/{locale}/student/homework/{id})` on success so
  any subsequent nav picks up the new state.
- `apps/web/src/app/[locale]/student/homework/[id]/block-attempt-panel.tsx`
  — new client component (~420 lines). Exports `BlockAttemptPanel` +
  `BlockAttemptLabels`. `displayStatusFor(attempt)` collapses the
  backend's three statuses + `solutionRevealedAt` into five display
  states (`NOT_ATTEMPTED / SUBMITTED / REVIEWED_OK /
  REVIEWED_NEEDS_WORK / SOLUTION_REVEALED`). `computeBaseFen(block)`
  replays the authored prefix on top of `startingFen` via `chess.js`
  to find the post-prefix position the student plays from. Interactive
  mode mounts `<Chessboard>` with `onPieceDrop`: each drag is validated
  with chess.js (queen promotion default), and the UCI is appended to
  `draftMoves`. Undo / Reset / Submit buttons sit beside the move list;
  Submit runs inside `useTransition` so the whole row stays disabled
  while pending. Read-only mode constructs a synthetic
  `BoardCardBlock` (`startingFen = baseFen`, `moves = attempt.moves`,
  `solution = null`) and hands it to `BoardCardView` with
  `canRevealSolution={false}`, so the stepper only replays the
  student's work — the authored prefix is baked into the starting
  position.
- `apps/web/src/app/[locale]/student/homework/[id]/detail-panels.tsx`
  — rewrote to hand each paired `(block, attempt)` to
  `BlockAttemptPanel` instead of the previous plain `BoardCardView`
  loop. Added `attempts: BlockAttemptView[]` to
  `StudentHomeworkDetail`, `buildAttemptLabels(t)` helper (mirrors
  `buildViewerLabels`), `pairBlocksWithAttempts(resources, attempts)`
  matcher (keyed on `resource.position === attempt.blockIndex`), and
  a `liveAttempts` local mirror with `onAttemptSubmitted(fresh)` that
  replaces any existing row for the same block or appends a new one.
- `apps/web/messages/{en,ru,uz}.json` — new `student.attempt.*`
  namespace (~18 keys) covering the five status labels, the play /
  retry instructions, the three button labels + pending copy, the
  teacher-note + meta labels, and the illegal-move notice. Key parity
  verified across all three locales.
- `apps/web/src/app/globals.css` — appended the `.student-hw-attempt*`
  block (card, head, title, five status-pill variants, meta, teacher
  note, play hint, board container capped at 360px, moves section +
  move-list chips, actions row, error, and a narrow-viewport override
  that widens the board and stacks the head).

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up (`docker compose up -d postgres`).
2. Sign in as admin (`admin@demo.local` / `admin12345`). Open a class
   the demo student is enrolled in (or enrol `student@demo.local`
   via **Add student**). Assign a new homework with at least one
   BOARD block that carries a recorded solution, e.g. start from
   the default position and record `e2e4` as the solution move. If
   you want to exercise the authored-prefix path, add a move prefix
   too (`1. e4 e5 2. Nf3` recorded on the block, with the solution
   picking up from Black's second move).
3. Log out, sign in as `student@demo.local`. Land on `/en/student`,
   click into the new homework.
4. Happy path — first attempt:
    1. Each board block now renders as a **Not attempted** card with
       an interactive board at the post-prefix position. Drag a
       move (e.g. `g8 → f6` on a Najdorf prefix). The move appears
       in the **Your moves** list as `g8f6` and the board animates.
    2. Drag a bad follow-up to test **Undo last** → the last UCI
       disappears, the board rolls back.
    3. Drag 2-3 moves and click **Submit**. The card flips to a
       **Submitted** pill; the interactive board is replaced with the
       read-only replay (stepper can walk through your moves).
       A meta line shows the submission timestamp.
5. Happy path — resubmit:
    1. Switch to admin, open the class, scroll to the homework's
       **Submissions** sheet. Because #40 isn't shipped yet, there's
       no review UI in the app — mark the attempt NEEDS_WORK via
       curl (see the #29–#35 entry for the review endpoint).
    2. Refresh the student page. The card's pill flips to
       **Needs work**; the teacher's note (if provided) appears in a
       highlighted box above the board; the board is interactive
       again with a "retry" instruction line.
    3. Play a new move sequence, submit again. Verify the history:
       the admin history endpoint should now show two rows (the
       older with `replacedById` set).
6. Edge cases worth eyeballing:
    - Try to drag an illegal move (e.g. `e2 → e5` from the start
       position) — the board snaps back, nothing is appended.
    - Submit with an empty move list — the button is disabled
       (`draftMoves.length === 0`), so this shouldn't be possible
       via the UI. If you force it, the action returns the
       `error.invalid` copy inline under the buttons.
    - A block whose `startingFen` won't parse (shouldn't happen in
       practice because the backend validates on write) falls back
       to the `viewer.invalidBlock` error copy instead of crashing.
    - Switch locale to `/ru/student/...` or `/uz/student/...`: the
       five status pills, the play / retry instructions, the buttons,
       and the teacher-note heading should all be fully translated.

### #29–#35 — Student-submitted solution moves (backend slice of #28)

Server-side foundation for the "student plays the solution on the
board" loop. New `homework_block_attempt` table stores one history
row per (homework, student, block) attempt, chained via
`replaced_by_id` so overwrites keep the audit trail but only the
latest row is "live". Students `POST` their UCI move list to submit;
teachers review each live attempt with OK / NEEDS_WORK + optional
note; on NEEDS_WORK the student can reveal the authored solution,
which un-strips the `solution` field on that block's payload in
subsequent `StudentHomeworkDetail` reads. History, per-block audit,
and "reviewing a replaced attempt → 409" are all enforced server-side.
No UI yet — frontend sub-tasks are still to be filed.

Files:

- `apps/api/src/main/resources/db/migration/V008__homework_block_attempt.sql`
  — new table with FKs to `homework`, `users`, and self-ref
  `replaced_by_id ON DELETE SET NULL`. Partial unique index
  `(homework_id, student_user_id, block_index) WHERE replaced_by_id IS NULL`
  enforces the "at most one live row per triple" invariant.
- `apps/api/src/main/resources/db/migration/V009__homework_block_attempt_defer_fk.sql`
  — alters the self-ref FK to `DEFERRABLE INITIALLY DEFERRED`. Without
  this, the overwrite path in `submitAttempt` blows up: the service
  stamps `prev.replaced_by_id = newId` *before* inserting the row that
  carries `newId` as its PK (this ordering is what keeps the partial
  unique index from briefly seeing two live rows), and an IMMEDIATE
  FK fails at flush() because the target doesn't exist yet. Deferring
  the check to commit time resolves cleanly — by commit the INSERT
  has populated the target row.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/BlockAttemptStatus.java`
  — enum `SUBMITTED / REVIEWED_OK / REVIEWED_NEEDS_WORK`.
  SOLUTION_REVEALED is derived from `REVIEWED_NEEDS_WORK` +
  non-null `solutionRevealedAt`, not a stored enum value.
- `apps/api/src/main/java/com/chessedu/platform/classes/model/HomeworkBlockAttempt.java`
  — JPA entity with `@JdbcTypeCode(SqlTypes.JSON) JsonNode moves`
  (mirrors `HomeworkResource.payload`) and auditing listener for
  `createdAt` / `updatedAt`. Explicit UUID set by the service, not
  `@GeneratedValue` (keeps overwrite ordering easier).
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/HomeworkBlockAttemptRepository.java`
  — "current" finders (`replacedById is null`) for block, student,
  homework, and cross-homework batch; history finders that include
  replaced rows ordered by `createdAt ASC`.
- `apps/api/src/main/java/com/chessedu/platform/classes/chess/ChessMoves.java`
  — extracted shared `applyLegalMove(Board, uci, ctx)` helper.
  `HomeworkResourceValidator` now delegates instead of owning a
  private copy.
- `apps/api/src/main/java/com/chessedu/platform/classes/chess/BlockAttemptValidator.java`
  — validates student moves (array, non-empty, ≤50, all textual,
  all legal starting from `startingFen + authored moves`). Returns a
  normalised `ArrayNode` for persistence.
- `apps/api/src/main/java/com/chessedu/platform/classes/HomeworkBlockAttemptService.java`
  — three transactional entry points:
  - `submitAttempt(principal, homeworkId, blockIndex, moves)` —
    validates enrolment, loads the block, validates moves, stamps
    the old live row's `replacedById` + `flush()`, inserts a fresh
    `SUBMITTED` row with a new UUID (partial-unique-index-safe).
  - `reviewAttempt(principal, attemptId, status, teacherNote)` —
    authorises via stored `homeworkId → classId`; rejects
    `replacedById != null` with 409; writes `reviewedBy` /
    `reviewedAt`; clears `solutionRevealedAt` on review so a
    previously-revealed attempt can't silently keep its reveal
    after the teacher flips it back to OK.
  - `revealSolution(principal, homeworkId, blockIndex)` — requires
    a live `REVIEWED_NEEDS_WORK` attempt with an authored
    `solution.uci` on the block; idempotent (second call returns
    the same row unchanged).
  Plus read helpers (`listMyAttempts`, `listAttemptsForAdmin`,
  `historyForAdmin`). `authoriseClass` / `requireStudent` are
  duplicated from `HomeworkService` for now — the class javadoc
  flags this for a future refactor.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/BlockAttemptView.java`
  — read shape returned by every block-attempt endpoint.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/SubmitBlockAttemptRequest.java`,
  `.../ReviewBlockAttemptRequest.java` — request bodies.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/HomeworkDetail.java`,
  `.../StudentHomeworkDetail.java` — both gained a trailing
  `List<BlockAttemptView> attempts` field. Admin detail carries the
  full roster's live attempts; student detail carries only the
  caller's.
- `apps/api/src/main/java/com/chessedu/platform/classes/HomeworkService.java`
  — `detail(...)` / `detailForStudent(...)` now fetch live
  attempts and pass them through. For the student path, any block
  index with a non-null `solutionRevealedAt` on the caller's live
  row skips the `stripSolution(JsonNode)` defensive pass, so the
  authored `solution.uci` flows back to the wire. Package-private
  `toBlockAttemptView(HomeworkBlockAttempt)` helper moved here so
  controllers don't need their own mapper.
- `apps/api/src/main/java/com/chessedu/platform/classes/MeHomeworkController.java`
  — `POST /api/v1/me/homework/{id}/blocks/{blockIndex}/attempts`
  (submit) and `POST /.../reveal-solution` (reveal).
- `apps/api/src/main/java/com/chessedu/platform/classes/AdminHomeworkController.java`
  — `POST /api/v1/admin/classes/{classId}/homework/{id}/attempts/{attemptId}/review`
  and `GET /.../students/{studentUserId}/blocks/{blockIndex}/attempts`
  (history, oldest first).
- `apps/api/src/test/java/com/chessedu/platform/classes/HomeworkBlockAttemptFlowIntegrationTest.java`
  — end-to-end test covering submit → review NEEDS_WORK → reveal
  (idempotent) → student detail un-strips solution → overwrite
  submission → history shows both rows with `replacedById` set on
  the older → reviewing a replaced attempt returns 409 → reviewing
  the live one returns 200 → admin detail shows only the live
  attempt. Plus rejection cases: empty moves (400), illegal move
  (400), reveal before review (409), reveal when no solution
  authored (409), non-enrolled student submit (404 — hides
  existence), unassigned teacher review (403).

How to check as a user:

Because there's no UI yet, verification is end-to-end via the HTTP
API. Frontend tasks (student interactive board + admin review) are
still to be filed — the CLAUDE.md entry for the first UI slice will
update this section with click-paths.

1. Start the stack: `./gradlew :api:bootRun` for the backend. The
   Postgres container needs to be up — `docker compose up -d postgres`
   from the repo root.
2. Sign in as admin and grab a token:
   ```bash
   ADMIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"admin@demo.local","password":"admin12345"}' \
     | jq -r .accessToken)
   ```
3. Enrol the demo student in a class (any existing class is fine;
   see the Dev-only seed entry below for `student@demo.local`).
   Then create a homework with a BOARD block that carries an
   authored `solution.uci`:
   ```bash
   curl -s -X POST \
     http://localhost:8080/api/v1/admin/classes/<CLASS_ID>/homework \
     -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
     -d '{
       "title":"Attempt flow check",
       "resources":[{"kind":"BOARD","payload":{
         "startingFen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
         "moves":[],
         "orientation":"WHITE",
         "solution":{"uci":["e2e4"]}}}]
     }' | jq
   ```
   Grab the returned `id` as `HW_ID`.
4. Log in as the demo student and submit an attempt:
   ```bash
   STU=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"student@demo.local","password":"student12345"}' \
     | jq -r .accessToken)
   curl -s -X POST \
     http://localhost:8080/api/v1/me/homework/$HW_ID/blocks/0/attempts \
     -H "authorization: Bearer $STU" -H 'content-type: application/json' \
     -d '{"moves":["d2d4"]}' | jq
   ```
   Expected: `status: "SUBMITTED"`, `replacedById: null`, `moves: ["d2d4"]`.
   Save the returned `id` as `ATT_ID`.
5. Teacher marks it needs work:
   ```bash
   curl -s -X POST \
     http://localhost:8080/api/v1/admin/classes/<CLASS_ID>/homework/$HW_ID/attempts/$ATT_ID/review \
     -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
     -d '{"status":"REVIEWED_NEEDS_WORK","teacherNote":"Try again"}' | jq
   ```
6. Student reveals the solution:
   ```bash
   curl -s -X POST \
     http://localhost:8080/api/v1/me/homework/$HW_ID/blocks/0/reveal-solution \
     -H "authorization: Bearer $STU" | jq '.solutionRevealedAt'
   ```
   Expected: a non-null timestamp.
7. Confirm the student detail now carries the authored solution on
   that block (it was stripped before reveal):
   ```bash
   curl -s http://localhost:8080/api/v1/me/homework/$HW_ID \
     -H "authorization: Bearer $STU" | jq '.resources[0].payload.solution'
   ```
   Expected: `{"uci":["e2e4"]}`. Compare against a second homework
   on the same class where no attempt has been reviewed — its
   `solution` key should still be absent.
8. Overwrite: resubmit a new attempt on the same block:
   ```bash
   curl -s -X POST \
     http://localhost:8080/api/v1/me/homework/$HW_ID/blocks/0/attempts \
     -H "authorization: Bearer $STU" -H 'content-type: application/json' \
     -d '{"moves":["e2e4"]}' | jq
   ```
   Hit the history endpoint and confirm both rows appear, oldest
   first, with the older one's `replacedById` pointing at the
   newer's id:
   ```bash
   curl -s "http://localhost:8080/api/v1/admin/classes/<CLASS_ID>/homework/$HW_ID/students/<STUDENT_USER_ID>/blocks/0/attempts" \
     -H "authorization: Bearer $ADMIN" | jq '.[] | {id, status, replacedById}'
   ```
9. Edge cases worth eyeballing:
   - `{"moves":[]}` on submit → 400 `VALIDATION_FAILED`.
   - `{"moves":["e2e5"]}` on the starting position → 400 (illegal).
   - Reveal before any review → 409 `CONFLICT`.
   - Reveal on a block with no authored solution → 409.
   - Reviewing the now-replaced `ATT_ID` after step 8 → 409
     (teacher actions must apply to what the student sees).
   - A student who's not enrolled in the class POSTing to the
     submit URL → 404 (hides homework existence, matches
     `detailForStudent`).
   - A teacher not assigned to the class calling the review
     endpoint → 403.

### Dev-only — Seed a demo student with login credentials

Students are profile-only rows in M3 (see `ChildrenService`'s "no
credentials — profile only" comment, and `InvitationService`'s
explicit `Role.STUDENT` refusal), so out of the box there's no way
to sign in as one to exercise the new `/student` shell. Extended the
local-profile `DemoDataSeeder` to also mint a demo student with a
known password and an ACTIVE membership in the demo school. No
production flow; local profile only. Future real-world credential
issuance (parent-issued invite token or school-admin-set-password)
is a separate task — see the "Missing" note below.

Files:

- `apps/api/src/main/java/com/chessedu/platform/bootstrap/DemoDataSeeder.java`
  — extracted a `seedUser(school, email, password, displayName, role)`
  helper with per-step idempotency (user / credentials / membership
  each created only if missing), then called it twice: once for the
  existing `admin@demo.local` SCHOOL_ADMIN and once for a new
  `student@demo.local` STUDENT. Idempotent on reboot; existing dev
  DBs get the student backfilled on next startup with an info log.

Credentials (after `./gradlew :api:bootRun` on the `local` profile):

- Admin: `admin@demo.local` / `admin12345`
- Student: `student@demo.local` / `student12345`

How to check as a user:

1. Run `./gradlew :api:bootRun` (local profile is the default). In the
   startup logs look for `Seeded demo user: role=STUDENT …` — if
   you've booted before, you'll see `Backfilled demo user: …` instead
   (creds + membership were added to the existing row).
2. `curl -s -X POST http://localhost:8080/api/v1/auth/login \
      -H 'content-type: application/json' \
      -d '{"email":"student@demo.local","password":"student12345"}' | jq`
   → should return a fresh access / refresh token pair.
3. In the web app at http://localhost:3100, sign in as the admin
   first and enrol the demo student in a class (scroll to Roster →
   **Add student**, pick Demo Student). Assign a homework.
4. Log out, sign in as `student@demo.local`. Land on `/en/student`
   with the assigned homework listed. Click through to exercise the
   viewer (see the #23/#27 entry below for the full walkthrough).

Missing / follow-up:

- No production path for student credentials exists yet. The proper
  fix belongs in `ChildrenService` (parent-issued invite or
  temporary password surfaced in the parent UI) and/or
  `InvitationService` (drop the `Role.STUDENT` refusal for school
  admins with a UI to set or reset a student password). File as a
  fresh task before shipping the `/student` shell to real users.

### #23 / #27 — Student homework viewer + admin preview

Two tasks shipped together because they share the same read-only board
card component. **#23** lights up a brand-new `/student/*` shell: a
list page that reads from `/api/v1/me/homework` and a detail page that
reads the new `/api/v1/me/homework/{id}` endpoint. The detail page
renders each authored `BOARD` block with step-through replay (◀ / ▶ /
⏮ / ⏭, clickable move list), and mark-as-done / mark-as-skipped /
clear chips that POST through `updateMyHomeworkAction` and revalidate
server-side. Solutions are stripped in the backend (defensive
`ObjectNode.deepCopy().remove("solution")` in `HomeworkService`) so
they never reach the student wire; belt-and-suspenders with
`canRevealSolution={false}` on the shared viewer. **#27** mounts the
same viewer in the admin Submissions sheet (above the per-student
chips) with `canRevealSolution={true}` so teachers can walk through
what they assigned alongside the class's responses. The login action
now routes STUDENT → `/student`.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/StudentHomeworkDetail.java`
  — new DTO mirroring `HomeworkSummary` + caller's own submission
  fields + an ordered `List<HomeworkResourceView>` (payloads already
  stripped by the time they land here).
- `apps/api/src/main/java/com/chessedu/platform/classes/HomeworkService.java`
  — added `detailForStudent(principal, homeworkId)` (same 404 shape
  as `updateMySubmission` — hides the existence of homework in
  classes the student isn't enrolled in, including archived ones) and
  a private `stripSolution(JsonNode)` helper using
  `((ObjectNode) payload).deepCopy().remove("solution")`. Imports
  `StudentHomeworkDetail` and Jackson's `ObjectNode`.
- `apps/api/src/main/java/com/chessedu/platform/classes/MeHomeworkController.java`
  — new `GET /{id}` endpoint that delegates to `detailForStudent`.
- `apps/web/src/components/board-card-view.tsx` — new i18n-agnostic
  shared component (~460 lines). Exports `BoardCardView`,
  `BoardCardBlock`, `BoardCardLabels`, `STARTING_FEN`,
  `parseBoardBlock`, `blocksFromResourcesForView`. Uses `chess.js`
  to build an FEN array indexed by ply (0 = start); state is
  `{ply, showSolution, solPly}`. Chessboard is mounted with
  `allowDragging: false` / `allowDrawingArrows: false` /
  `animationDurationInMs: 150`. Invalid block fallback just shows
  `labels.invalidBlock` rather than crashing.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — added `boardViewerLabels(t)` helper + a `<section className="hw-sheet__boards">`
  inside `SubmissionSheet` that maps the lazy-fetched `detail.resources`
  through `blocksFromResourcesForView` into `BoardCardView` with
  `canRevealSolution={true}` and `idPrefix="admin-sheet"`.
- `apps/web/src/app/[locale]/student/layout.tsx` — new. Mirrors
  `/parent/layout.tsx`: locale gate + access-token redirect.
- `apps/web/src/app/[locale]/student/actions.ts` — new server
  actions. `updateMyHomeworkAction(prev, formData)` parses
  homeworkId/locale/status (empty string = clear), PUTs to
  `/api/v1/me/homework/{id}/status`, and revalidates both
  `/{locale}/student` and `/{locale}/student/homework/{id}`.
  `logoutAction` duplicates the parent's intentionally to keep the
  route trees decoupled.
- `apps/web/src/app/[locale]/student/logout-button.tsx` — new
  client island, same shape as parent's.
- `apps/web/src/app/[locale]/student/page.tsx` — new server
  component. Role-gates STUDENT (bounces anyone else to `/admin`),
  fetches `/api/v1/me/homework`, renders the flat list with a status
  pill per row. Uses `Link from "@/i18n/routing"` with
  `href={`/student/homework/${h.id}`}` (no locale prefix — next-intl
  adds it) to stay on the typed-routes happy path.
- `apps/web/src/app/[locale]/student/homework/[id]/page.tsx` — new
  server page. Fetches `StudentHomeworkDetail` from the new endpoint,
  renders title + breadcrumb + class name + due/created meta line,
  then hands off to `<StudentHomeworkDetailPanels>`. Handles 401/403
  (session logout) and 404 (friendly empty state with a back link).
- `apps/web/src/app/[locale]/student/homework/[id]/detail-panels.tsx`
  — new client component. Builds `BoardCardLabels` from
  `useTranslations("student")`, renders the status pill + the
  mark-done/mark-skipped/clear form wired to `useActionState`
  (pending-aware disable, `aria-pressed` for the current chip),
  shows the teacher's instructions (if any), then the list of
  `BoardCardView`s with `canRevealSolution={false}` and
  `idPrefix="student-hw"`.
- `apps/web/src/app/[locale]/auth/login/actions.ts` — `homeForRole`
  now has a `STUDENT` branch (returns `"student"`), and the post-
  redirect switch has a matching literal `redirect(/${locale}/student)`
  so next-intl's typed-routes check stays green.
- `apps/web/messages/{en,ru,uz}.json` — added the
  `admin.classes.homework.sheet.boardsHeading` key and the
  `admin.classes.homework.viewer.*` subtree (17 keys) for the admin
  preview; added the whole `student.*` namespace (dashboard / homework
  list / row / status / detail / viewer / error — 50+ keys) for the
  student shell. Key parity verified across all three locales
  (en: 468, ru: 468, uz: 468).
- `apps/web/src/app/globals.css` — appended the `.bcv*` styles (used
  by both surfaces) plus `.hw-sheet__boards*` for the admin preview
  and the full `.student-hw*` / `.student-hw-detail*` block (cards,
  rows, status pills, responsive grid) for the student shell.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up
   (`docker compose up -d postgres`).
2. You need a student account with at least one active homework.
   The local-profile seeder now creates one automatically
   (`DemoDataSeeder`): `student@demo.local` / `student12345`.
   First sign in as admin (`admin@demo.local` / `admin12345`), open
   any class (or create one) and **enrol the demo student** via
   the Members / roster surface. Then create a new homework via
   **New homework** → fill title, add one board block with 2–3
   moves (e.g. drag `e2e4`, `e7e5`, `g1f3`) and submit.
3. Log out, then sign in as `student@demo.local`. **You should now
   land on `/en/student`** (not `/admin`). Expected: a card with
   "Hi, Demo Student" and the flat list of their homework,
   newest-first, each row showing a "Not started" pill.
4. Happy path — viewer:
    1. Click a row. The detail page shows a breadcrumb **←
       Back to homework**, the title, class name, due/created meta,
       a **Your status** pill (Not started), and (if present) the
       teacher's instructions.
    2. Under **Board exercises**, each block renders with its title
       (or "Untitled board"), the starting position, a "To move:
       White/Black" label, and a stepper row: **⏮ ◀ Move 0 of N ▶ ⏭**.
    3. Click **▶** (or the move list chips) to step forward; the
       board animates between positions. Jump directly by clicking
       e.g. "Move 2". Go back to start with **⏮**.
    4. There should be **no "Show solution" disclosure** on the
       student side — verify by peeking at the Network tab: the
       response from `/api/v1/me/homework/{id}` carries no
       `solution` key on any resource's payload.
5. Happy path — marking:
    1. Click **Mark as done**. The pill flips to "Done" and a
       "Submitted …" timestamp appears. The chip now shows as the
       primary button; the other two stay ghost.
    2. Click **Mark as skipped**. The pill flips to "Skipped".
    3. Click **Clear status**. Pill goes back to "Not started"
       and the Submitted timestamp disappears. The Clear button
       also disappears (only rendered when status is non-empty).
    4. Return to the list via the breadcrumb. The row pill
       reflects the latest status (confirms list-page revalidation).
6. Admin-side preview (#27):
    1. Log back in as admin. Open the same class,
       scroll to **Homework**, click **Submissions** on the row you
       created.
    2. The sheet loads: first a new **Chess content preview** section
       at the top with each board rendered via the same viewer (plus
       a **Show solution** button if the block has a recorded
       solution — try adding one in the author editor first to see
       it here).
    3. Scroll past the boards: the per-student chips (DONE /
       SKIPPED / Clear) are right underneath, unchanged.
7. Edge cases worth eyeballing:
    - A student opens `/en/student/homework/<random-uuid>` →
      shows the "Homework not found" friendly page with a back link.
      Same if they paste the id of a homework from another class
      they're not enrolled in (no information leak).
    - A STUDENT who tries to open `/en/admin` is redirected to login
      → back to `/en/student` (the role gate on parent/admin pages
      bounces non-matching roles).
    - The **Show solution** disclosure on the admin side is the
      only way to reveal solution moves; the student's
      `BoardCardView` instance is hard-wired with
      `canRevealSolution={false}`, so even in-browser hacking
      doesn't help (the payload doesn't contain it anyway).
    - Switch locale to `/ru/student` or `/uz/student` — all viewer
      labels, the stepper, the status chips, and the boards heading
      should be fully translated. Russian uses plural-aware
      `movesCount`; Uzbek uses one/other.

### #26 — Show session + instructions on homework row

Filled in the two gaps spotted on the Homework list: the anchored
session wasn't shown anywhere on the row, and author-written
instructions only appeared inside the expanded submission sheet. Added
a "Session · 20 Apr 18:00" pill to the row meta bar (rendered only
when the homework is anchored to a session) and a single-line,
ellipsis-truncated instructions preview underneath the meta bar, with
the full text hovering on `title` for quick inspection. To keep the
row render cheap the backend `HomeworkSummary` record gained two
fields: `instructions` (passthrough from the entity) and
`sessionStartsAt` (denormalised from `class_session.starts_at`) —
denormalising the session start avoids a second fetch and, more
importantly, covers homework anchored to *past* sessions that the
upcoming-only `sessions` prop wouldn't include. While in the file I
also tidied two pre-existing TS `noUncheckedIndexedAccess` errors in
`board-blocks-editor.tsx` (left over from Task #24) so the
frontend typecheck is green again.

Files:

- `apps/api/src/main/java/com/chessedu/platform/classes/dto/HomeworkSummary.java`
  — added `instructions` and `sessionStartsAt` record components with
  docstrings explaining the denormalisation.
- `apps/api/src/main/java/com/chessedu/platform/classes/HomeworkService.java`
  — `list(...)` now batches a `sessions.findAllById(...)` for every
  homework that carries a `sessionId`, caches the starts into a
  `Map<UUID, Instant>`, and feeds both new fields into the record
  constructor. Empty when no row has a session, so there's no extra
  query in the common "no session anchor" path.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-actions.ts`
  — matching TypeScript additions to `HomeworkSummary` with inline
  JSDoc covering the null / past-session edge cases.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — `HomeworkRow` computes `sessionLabel` (preferring the summary's
  denormalised `sessionStartsAt`, with the `sessions` prop as a
  defence-in-depth fallback) and `instructionsPreview` via a new
  `truncatePreview(text, 140)` helper. JSX adds the session pill to
  the existing meta bar and a `.hw-row__instructions` paragraph under
  it; `title={homework.instructions}` exposes the full text on hover.
- `apps/web/src/app/[locale]/admin/classes/[id]/board-blocks-editor.tsx`
  — two non-null assertions (`ranks[r]!`, `piece[1]!`) with
  explanatory comments — strictly typecheck noise, behaviour
  unchanged.
- `apps/web/messages/{en,ru,uz}.json` — added
  `admin.classes.homework.row.sessionOn` in all three locales.
- `apps/web/src/app/globals.css` — added `.hw-meta-item--session`
  (matches the existing due-pill styling) and a single-line
  ellipsis-truncating `.hw-row__instructions` rule.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up
   (`docker compose up -d postgres`).
2. Sign in as a school admin — e.g. `admin@kingside.dev` with the
   password in `docs/dev-seed.md` — and open a class that has at
   least one upcoming session and at least one enrolled student:
   `/en/admin/classes/<classId>`.
3. Scroll to **Homework**. Click **New homework**, fill a title,
   pick an **Attach to session** entry from the dropdown, type a
   few sentences of instructions (e.g. "Solve puzzles 1–10 in your
   workbook. Focus on tactics sections A and B. Submit by class on
   Monday."), optionally pick a due date, then submit.
4. Expected: the freshly-created row now shows a **Session · &lt;when&gt;**
   pill alongside the due / created meta items, and a second line
   underneath the meta bar with the instructions flattened onto one
   line and truncated with "…" if longer than ~140 chars. Hover the
   preview — the tooltip should reveal the full instruction text.
5. Edge case — no session, no instructions: create another homework
   leaving both the session dropdown on "No session" and the
   instructions field empty. Row shows only the standard "No due
   date" / "Created …" items plus the tally; neither the session
   pill nor the instructions preview renders.
6. Edge case — past session: if you have a homework anchored to a
   session that's already in the past, it should still show the
   session pill with the past date/time. (The upcoming-only
   `sessions` prop doesn't carry it, so this proves the backend's
   `sessionStartsAt` denormalisation is doing its job.)
7. Edge case — multiline instructions: the preview collapses
   whitespace, so "Line one.\nLine two." renders as
   "Line one. Line two." on a single line.
8. Switch locale to `/ru/...` or `/uz/...` and confirm the session
   pill reads "Занятие · …" / "Dars · …" respectively.

### #25 — Fix fresh homework appearing archived

Freshly-created homework was being rendered with the **Archived** pill
and routed into the archived bucket, even though the backend correctly
left `archivedAt = null`. Root cause: the API runs with
`spring.jackson.default-property-inclusion: non_null`, so null fields
are stripped from JSON responses — `archivedAt` arrives at the client
as `undefined`, not `null`. The filter in `page.tsx` and the row check
in `homework-panels.tsx` used strict `=== null` / `!== null`, so
`undefined` flipped them the wrong way. Switched those three call
sites to loose equality (`== null` / `!= null`) so both shapes route
correctly. Left the global Jackson setting alone — lots of existing
integration tests rely on `.doesNotExist()` for null response fields,
so a broader backend change would cascade.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` — swapped
  the two `homework.filter(...)` predicates to use nullish equality;
  added a short comment pointing at the Jackson config so the next
  agent doesn't re-encounter this.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx`
  — same treatment on the `isArchived` line inside `HomeworkRow`.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up
   (`docker compose up -d postgres`).
2. Sign in as a school admin — e.g. `admin@kingside.dev` with the
   password in `docs/dev-seed.md` — and open a class that has at
   least one enrolled student: `/en/admin/classes/<classId>`.
3. Scroll to **Homework**. Click **New homework**, enter a title,
   optionally pick an upcoming session and a due date, then submit.
4. Expected: the row now lands in the default (active) list with
   **no** "Archived" pill. The **Show archived (N)** toggle in the
   top-right only shows a count when there are actually archived
   rows — it should not count this new assignment.
5. Regression check: click **Archive** on any row, confirm the
   prompt. The row disappears from the active list and appears
   under **Show archived** with the Archived pill. Toggle back and
   the active list still shows the remaining active assignments
   correctly.
6. Edge case — no archived rows: if the class has no archived
   homework, the **Show archived** toggle should not render at all
   (the count is 0 and the button is conditionally rendered).

### #24 — Position editor with drag-and-drop palette

User-friendly starting-position editor for each board block. The block
card now has a **Setup position** / **Add moves** tab strip; Setup shows
the board between two palettes of draggable spare pieces (opposite
colour above, matching colour below — flips with orientation), plus
radios for side to move and checkboxes for the four castling flags.
Drag a spare onto a square to place; drag a board piece onto another
square to move; drag it off the board (or right-click its square) to
remove. Two quick buttons — **Fill starting position** and **Empty
board** — plus an optional **Advanced: edit FEN directly** disclosure
for power users. Any setup edit clears recorded moves + solution
(they'd be invalidated by the new FEN anyway). Castling flags are
auto-stripped on every save if the relevant king/rook isn't on its
home square, so the FEN we store stays internally consistent.

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/board-blocks-editor.tsx`
  — heavy refactor of `BoardBlockCard`. Added `SetupPanel` subcomponent
  that wraps both palettes and the board in a single
  `<ChessboardProvider>` (react-chessboard v5's `Chessboard` detects
  the existing provider via its internal `isWrapped` flag and just
  renders its `Board` inside our DnD context — so `SparePiece` works
  out of the box without any deep imports). Added permissive FEN
  helpers: `parseFenForSetup`, `composeFen`, `sanitizeCastling`,
  `topPalette` / `bottomPalette`, and `EMPTY_FEN`. The orientation
  radio moved from the sidebar into the card header so it applies to
  both tabs. Mode state defaults to Setup when `moves` is empty and
  Moves otherwise. An inline warning appears in the Moves tab if the
  current starting FEN isn't a legal chess position (chess.js can't
  replay moves on an illegal board, so drag-moves are disabled too).
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx` —
  `boardEditorLabels(t)` now threads the ~20 new `boards.setup*` /
  `boards.mode*` keys through to the editor. No other changes.
- `apps/web/messages/{en,ru,uz}.json` — added `modeSetup`, `modeMoves`,
  `setupFillStart`, `setupEmpty`, `setupPaletteHint`,
  `setupRemoveHint`, `setupSideToMove`, `setupCastling`,
  `setupCastleK/Q/k/q`, `setupAdvancedShow/Hide`,
  `setupIllegalPositionNotice`, `setupHint`, and
  `switchToSetupWarning`. Key parity verified across all three
  locales (54 keys each).
- `apps/web/src/app/globals.css` — added `.hw-board-card__tabs`,
  `.hw-board-card__setup*`, `.hw-board-card__palette*`,
  `.hw-board-card__side` / `__castling`, `.hw-board-card__advanced`,
  `.hw-board-card__notice`, and narrow-viewport overrides. Extended
  the head grid to fit the new orientation fieldset
  (`auto 1fr auto auto`).

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm --filter @chess-edu/web dev` (port 3100) for
   the frontend. Postgres must be up (`docker compose up -d postgres`).
2. Sign in as a school admin or as an assigned teacher — the dev seed
   has `admin@kingside.dev` with the password in `docs/dev-seed.md`.
3. Open a class with at least one enrolled student:
   `/en/admin/classes/<classId>`. Scroll to **Homework** → click
   **New homework**, fill a **Title**, then click **Add board**. The
   block card now opens on the **Setup position** tab.
4. Happy path — build a position by hand:
    1. Click **Empty board** (just to confirm — it's already empty on
       a fresh block).
    2. Drag a white king from the bottom palette onto **e1**. The
       board updates instantly; the palette slot stays in place
       (spare pieces aren't consumed).
    3. Drag a black king from the top palette onto **e8**.
    4. Drag a white rook onto **a1**, a white rook onto **h1**. The
       **White O-O** and **White O-O-O** checkboxes become enabled;
       tick both.
    5. Flip **Side to move** to **Black**.
    6. Open the **Advanced: edit FEN directly** disclosure — the
       field should read approximately
       `4k3/8/8/8/8/8/8/R3K2R b KQ - 0 1`.
5. Happy path — start from the full initial array:
    1. On another new block, click **Fill starting position**. All 32
       pieces appear; all four castling checkboxes become enabled and
       ticked. Side to move is **White**.
    2. Switch to the **Add moves** tab. The existing move recorder
       shows the same position; drag `e2 → e4` and confirm the move
       appears in the list.
6. Happy path — flip orientation:
    1. In the card header, choose **Black at bottom**. Both palettes
       and the board flip. The black pieces are now below, white above
       — still drag-and-drop.
7. Edge cases worth eyeballing:
    - **Right-click removal**: drag a white pawn onto **d4**, then
      right-click on **d4** — the pawn disappears. (In the Moves tab,
      right-click is swallowed by arrow drawing; setup disables
      arrows, so it works there.)
    - **Drag off board**: place a knight, then drag it off the edge
      — the library accepts the drop and the square clears.
    - **Castling clamp**: fill the starting position, then drag the
      white king from **e1** onto **e2**. The two white castling
      checkboxes auto-uncheck (because wK isn't on e1 anymore) and
      grey out. Move the king back to **e1** — the checkboxes become
      enabled again but stay unchecked (returning the king doesn't
      restore the right; the user must re-tick). The FEN in the
      advanced box reflects this.
    - **Illegal mid-setup position**: start from empty, drag in only a
      white king. Switch to **Add moves** — an orange warning banner
      says the position isn't legal, and dragging pieces on the board
      is disabled until you place a black king.
    - **Clearing moves on setup edit**: in Moves tab, play `e2e4`;
      switch to Setup, drag any piece (e.g. the c1 bishop to b2). The
      moves list is silently cleared (since the new FEN would
      invalidate them). This is intentional — the warning copy is in
      `boards.switchToSetupWarning` if we want to surface it later.
    - Switch locale to `/ru/...` or `/uz/...` and reopen the panel —
      all new labels (tabs, palette hint, castling, etc.) should be
      translated.

### #22 — Homework admin UI: chess content editor

Author-side UI for attaching one or more chess `BOARD` blocks to a
homework, mounted inline inside the existing New / Edit homework forms
below the session grid. Each block has a FEN, a move prefix (editable
via both drag-on-board AND paste-PGN), an orientation toggle, an
optional title, and an optional collapsible solution (hidden from
students — validated by the backend the same way). Sends the serialised
list to the backend via the existing `resources` field; three-state
semantics preserved on PATCH (untouched = leave alone, emptied = clear,
populated = replace).

Files:

- `apps/web/package.json` — added `chess.js@^1.4.0` (SAN/PGN parser +
  legality checks in the browser) and `react-chessboard@^5.10.0` (board
  component, React 19 compatible).
- `apps/web/src/app/[locale]/admin/classes/[id]/board-blocks-editor.tsx`
  — new client component. `BoardBlocksEditor` renders the list UI,
  `BoardBlockCard` is the per-block editor (FEN input with live validity,
  draggable chessboard that appends UCI to the prefix, paste-PGN textarea
  with Replace/Append actions, orientation radio, collapsible solution
  section that mirrors the prefix UX). Also exports `blocksFromResources`
  / `resourcesFromBlocks` for round-tripping and `STARTING_FEN` constant.
  PGN is parsed by first letting chess.js consume the text (handles
  headers, comments, variations), then replaying the resulting SAN
  tokens against the block's declared FEN so the author gets clear
  feedback if they paste a game that doesn't match their start position.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx` —
  `NewHomeworkForm` owns a `blocks: BoardBlock[]` state and serialises
  it into a hidden `resources` input only when non-empty.
  `EditHomeworkForm` now lazy-fetches the homework detail on first
  render (pattern borrowed from `SubmissionSheet`), hydrates existing
  blocks via `blocksFromResources`, and only attaches the hidden
  `resources` input once the author has touched the editor
  (`blocksTouched`). Added `boardEditorLabels(t)` helper to feed
  next-intl strings into the i18n-agnostic editor component.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-actions.ts` —
  `HomeworkDetail` type now includes `resources: HomeworkResourceView[]`.
  Added `parseResources(formData)` with three-state semantics (missing
  key = `null` = leave alone; empty = clear; list = replace) and
  `toBackendResource(...)` that packs title + payload. Wired into both
  `createHomeworkAction` (only sends when non-empty) and
  `updateHomeworkAction` (passes through the three states to the PATCH
  body).
- `apps/web/messages/{en,ru,uz}.json` — added the `admin.classes.homework.boards.*`
  key tree (~30 strings covering the editor surface + loading / error
  states).
- `apps/web/src/app/globals.css` — appended the `.hw-boards` /
  `.hw-board-card` style block (wrapper card, 2-column body grid that
  collapses on narrow screens, move-list chips, PGN actions row,
  solution panel).

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && pnpm install && pnpm --filter @chess-edu/web dev`
   (port 3100) for the frontend. Postgres must be up
   (`docker compose up -d postgres`).
2. Sign in as a school admin or as an assigned teacher — the dev seed
   has `admin@kingside.dev` with the password in `docs/dev-seed.md`.
   The editor is gated on the same `canMarkAttendance` rule as
   attendance / homework CRUD; a teacher who's not in the class still
   sees the panel but without the action buttons.
3. Open a class with at least one enrolled student:
   `/en/admin/classes/<classId>`. Scroll to **Homework**.
4. Happy path — create with a single board block:
    1. Click **New homework**, fill a **Title**.
    2. Scroll to **Chess content** → click **Add board**.
    3. Drag `e2 → e4` on the board. The tally at top-right of the block
       updates to "1 move(s)" and the side-to-move label flips to
       **Black**.
    4. Drag `e7 → e5`, then `g1 → f3` — the move list now shows
       `e2e4`, `e7e5`, `g1f3`.
    5. Click **Black at bottom** — board flips.
    6. Submit. The form closes, the new row appears at the top of the
       list with its tally.
5. Happy path — paste a PGN instead:
    1. Add a new block.
    2. Paste into the PGN box:
       `1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6`.
    3. Click **Replace moves**. The board advances to the Najdorf
       tabiya, the move list shows 10 UCI tokens.
6. Happy path — attach a solution:
    1. On any block, click **Add solution**. A second board appears
       underneath.
    2. Drag a move for whoever's to move — it gets appended to the
       solution list but is hidden from the prefix.
    3. Submit. Re-open **Edit** on the row to confirm the solution
       round-trips.
7. Happy path — edit an existing homework:
    1. Click **Edit** on the row. The block editor shows a brief
       "Loading chess content…" line while the detail fetch completes.
    2. The existing blocks hydrate; drag a new move on one of them.
    3. Click **Save changes** → row re-renders, submissions stay
       untouched.
8. Edge cases worth eyeballing:
    - Type an invalid FEN (`totally-not-a-fen`) and blur → inline
      "This is not a valid position." message; the board doesn't
      update. Fix the FEN, blur → board refreshes.
    - Paste garbage into the PGN box → "Could not parse those moves."
      under the Replace/Append buttons.
    - Paste a game that doesn't fit the custom starting FEN
      (e.g. starting FEN is a mid-game position but the PGN starts with
      `1. e4`) → the same parse error, because the first SAN token
      isn't legal in the declared position.
    - On the New form, if you click **Add board** then **Remove** and
      submit without any blocks, the create request omits `resources`
      entirely (leaves no blocks attached — the old path).
    - On the Edit form, if you reopen Edit and submit without touching
      the block editor, the PATCH body omits `resources`, so existing
      blocks are preserved. If you remove every block, the form sends
      `resources: []` and all blocks are cleared.
    - Switch locale to `/ru/...` or `/uz/...` and reopen the panel —
      all editor labels, placeholders, and error messages should be
      translated.

### #21 — Homework resources schema + backend

Added a `homework_resource` table and a server-side chess validator so
homework can carry one or more authored content blocks (v1: a `BOARD`
block with a starting FEN, optional UCI move prefix, orientation, and an
optional hidden UCI solution). The admin create/update endpoints now
accept an ordered `resources` list and replace-on-write; the detail
payload echoes back the stored blocks in order. Validation runs on every
write (illegal FEN, illegal move in sequence, illegal solution move, or
unknown `kind` all 400 out). No UI surface yet — Tasks #22 / #23 light
up author + viewer experiences.

Files:

- `apps/api/build.gradle.kts` — added `com.github.bhlangonijr:chesslib:1.3.6`.
- `settings.gradle.kts` — added a group-scoped JitPack repository (only
  `com.github.bhlangonijr` resolves via JitPack; everything else stays on
  Maven Central).
- `apps/api/src/main/resources/db/migration/V007__homework_resource.sql`
  — new table (CASCADE on homework delete, `UNIQUE(homework_id, position)`,
  JSONB `payload`, open-ended `kind`).
- `apps/api/src/main/java/com/chessedu/platform/classes/model/HomeworkResource.java`
  — JPA entity with `@JdbcTypeCode(SqlTypes.JSON) JsonNode payload`.
- `apps/api/src/main/java/com/chessedu/platform/classes/repo/HomeworkResourceRepository.java`
  — `findByHomeworkIdOrderByPositionAsc` + bulk `deleteAllByHomeworkId`
  used for replace-on-update.
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/{HomeworkResourceView,HomeworkResourceInput}.java`
  — read/write DTOs (position is server-assigned from list index).
- `apps/api/src/main/java/com/chessedu/platform/classes/dto/{HomeworkDetail,CreateHomeworkRequest,UpdateHomeworkRequest}.java`
  — added the `resources` field (create: optional; update: `null`=leave
  alone, `[]`=clear, populated=replace).
- `apps/api/src/main/java/com/chessedu/platform/classes/chess/HomeworkResourceValidator.java`
  — FEN + UCI + orientation validation using chesslib; normalises
  orientation to lowercase, lowercases UCI, defaults orientation to
  `"white"`.
- `apps/api/src/main/java/com/chessedu/platform/classes/HomeworkService.java`
  — wired validator + repo into create/update/detail.
- `apps/api/src/test/java/com/chessedu/platform/classes/HomeworkFlowIntegrationTest.java`
  — five new tests covering happy path ordering/normalisation, replace/
  clear/leave-alone PATCH semantics, and the four main rejection paths
  (illegal FEN, illegal prefix move, illegal solution move, unknown kind).

How to check as a user:

Because there's no UI in this task, verification is end-to-end via the
HTTP API. (Task #22 will add the author UI; Task #23 the student viewer.)

1. Start the stack: `./gradlew :api:bootRun` for the backend (wait for
   `Started Application`). The Postgres container needs to be up —
   `docker compose up -d postgres` from the repo root.
2. Get a JWT. Sign in as a school admin (seed account in
   `docs/dev-seed.md`, e.g. `admin@kingside.dev`):
   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'content-type: application/json' \
     -d '{"email":"admin@kingside.dev","password":"<from docs/dev-seed.md>"}' \
     | jq -r .accessToken)
   ```
3. Pick (or create) a class with at least one enrolled student. You can
   reuse any class visible at `/en/admin/classes` — grab its `id` from
   the URL.
4. Happy path — create homework with a board block:
   ```bash
   curl -s -X POST \
     http://localhost:8080/api/v1/admin/classes/<CLASS_ID>/homework \
     -H "authorization: Bearer $TOKEN" \
     -H 'content-type: application/json' \
     -d '{
       "title":"Board block check",
       "resources":[{
         "kind":"BOARD",
         "title":"Open with 1.e4 e5 2.Nf3",
         "payload":{
           "startingFen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
           "moves":["e2e4","e7e5","g1f3"],
           "orientation":"BLACK",
           "solution":{"uci":["b8c6"]}
         }
       }]
     }' | jq
   ```
   The response should include `"resources"` with one entry whose
   `position` is `0`, `orientation` is `"black"` (lowercased), and
   `solution.uci[0]` is `"b8c6"`.
5. Happy path — GET it back and confirm it round-trips:
   ```bash
   curl -s http://localhost:8080/api/v1/admin/classes/<CLASS_ID>/homework/<HW_ID> \
     -H "authorization: Bearer $TOKEN" | jq '.resources'
   ```
6. Happy path — PATCH to replace resources (same URL, `PATCH`, body
   `{"resources":[{"kind":"BOARD","payload":{"startingFen":"..."}}]}`).
   A `PATCH` with `{"resources":[]}` clears all blocks. A `PATCH` that
   omits the field leaves blocks alone — verify by patching just the
   title and re-GETting.
7. Edge cases worth eyeballing:
    - Illegal FEN: payload `{"startingFen":"not-a-fen"}` → 400 with
      `VALIDATION_FAILED` body.
    - Illegal move in the prefix: `{"startingFen":"<start>","moves":["e2e5"]}` → 400.
    - Unknown kind: `{"kind":"QUIZ","payload":{"q":"?"}}` → 400.
    - Illegal solution move: moves list puts black to move, solution
      plays a black move anyway → 400.
8. Existing admin UI at `/en/admin/classes/<id>` still works — the new
   `resources` field is simply unused by the v1 homework editor (Task
   #22 lights it up). Nothing in the current UI should render
   differently.

### #20 — Homework admin UI: list + CRUD + submissions

Full homework surface on the class detail page, below Schedule. Active +
archived tabs (archived hidden by default, shown via toggle when non-empty),
inline new-homework form, per-row edit/archive, and an expandable submission
sheet with DONE / SKIPPED / clear chips per student. Gated on the same
`canMarkAttendance` rule as attendance (school/platform admin always;
teachers only on classes they're assigned to).

Files:

- `apps/web/src/app/[locale]/admin/classes/[id]/homework-actions.ts` — server
  actions (read detail, create, update, archive, update submission) plus
  `HomeworkSummary` / `HomeworkDetail` types.
- `apps/web/src/app/[locale]/admin/classes/[id]/homework-panels.tsx` — full
  client component tree (`HomeworkPanels`, `NewHomeworkForm`, `HomeworkRow`,
  `Tally`, `EditHomeworkForm`, `ArchiveButton`, `SubmissionSheet`,
  `SessionSelect`).
- `apps/web/src/app/[locale]/admin/classes/[id]/page.tsx` — added a 6th
  parallel fetch with `?includeArchived=true` and split by `archivedAt`.
- `apps/web/messages/{en,ru,uz}.json` — `admin.classes.homework` key tree.
- `apps/web/src/app/globals.css` — `.hw-*` styles (card, rows, tally pills,
  submission chips, sheet) appended after the attendance block.

How to check as a user:

1. Start the stack: `./gradlew :api:bootRun` for the backend and
   `cd apps/web && npm run dev` (port 3100) for the frontend. Make sure the
   Postgres container is up (`docker compose up -d postgres`).
2. Sign in as a school admin (or platform admin) — the dev seed includes
   `admin@kingside.dev` / the password in `docs/dev-seed.md`. Teachers
   assigned to the class work too and should see the same UI; teachers
   *not* assigned should see the panel with the action buttons hidden
   (read-only).
3. Open a class that has at least one enrolled student:
   `/en/admin/classes/<classId>`. Scroll past **Roster** and **Schedule**
   — the new **Homework** card is the last section above the back link.
4. Happy path — create: click **New homework**, fill **Title**, optionally
   leave instructions + due date blank, submit. The form should close and
   the new row should appear at the top of the list with `0 done · 0 skipped
   · N unmarked`.
5. Happy path — mark submissions: click **Submissions** on the new row.
   A per-student sheet loads. Click **Done** on one student, **Skipped** on
   another, then **Clear** on the first. The tally pills on the row should
   update live after each click (page-level `router.refresh()`).
6. Happy path — edit: click **Edit** on a row, change the title, tick
   **Remove due date**, save. Row re-renders with the new title and
   `No due date`.
7. Happy path — archive: click **Archive**, confirm the native prompt.
   Row disappears from the active list and a **Show archived (N)** toggle
   appears at the top right. Flip the toggle to see archived rows with
   an `Archived` pill; edit / archive buttons are hidden there.
8. Edge cases worth eyeballing:
    - Create with only a due date (no time) or only a time (no date) —
      should show the `error.invalid` translation under the submit button.
    - Create with a due date + time — formatted due label should read in
      Asia/Tashkent (+05:00), not browser-local.
    - Try the page as a teacher who is NOT assigned to the class: the
      card should render read-only (no new/edit/archive buttons, chips
      disabled). Confirmed by the backend too — even a crafted PATCH
      returns 403.
    - Switch locale to `/ru/...` or `/uz/...` and reopen the panel — all
      labels, buttons, and error messages should be translated.
