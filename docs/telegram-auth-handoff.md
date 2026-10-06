# Telegram Auth — session handoff

Pick-up notes for continuing the Telegram-auth umbrella in Claude Code.
The detailed per-slice record lives in `CLAUDE.md` (entries
**"Telegram Auth A"** and **"Telegram Auth B"**, newest-first at the top
of `## Completed tasks`) — Claude Code reads that automatically. This
file is only the live state those entries don't capture: decisions,
what's next, and blockers.

## What this feature is

Telegram-based signup + sign-in for **non-student roles** (school admin,
teacher, parent). Invitation-only — no self-signup. Two flows, both
ending at `/<locale>/auth/accept?ott=<token>` → web redeems a one-time
token (OTT) for a JWT pair:

- **First signup — bot-driven.** `/start INV_<token>` → bot validates the
  invitation → Telegram "Share Contact" button captures a verified phone
  → bot creates the user + telegram_account + redeems the invite → replies
  with the `/auth/accept` deeplink.
- **Repeat sign-in — web Login Widget.** HMAC-verified against the bot
  token, keyed on the same `telegram_account` row by `telegram_id`. Mints
  an OTT internally and redeems it the same way.

Email/password stays as a visible fallback on `/auth/login`.

## Locked decisions (all confirmed with Bulat)

- **Spring RestClient** for the bot worker (not telegrambots lib, not raw
  `java.net.http`).
- **Long-polling** via `@Scheduled(fixedDelay)` (not webhooks).
- **Bot worker is off by default** — `@ConditionalOnProperty(chessedu.telegram.enabled=true)`.
- **Email/password form stays visible** next to the widget.
- **OTT deeplink both times** — one web redemption path for both flows.
- **Build order:** A → B → C → D (signup first, all localhost-testable),
  then E. The Login-Widget endpoint + button are deferred until a prod
  domain exists for `@BotFather /setdomain`.

## Status

- **Task A — schema + entities + repos** ✅ (V012 migration,
  `telegram_account` + `telegram_auth_token` tables, `invitation` gains
  `delivery_channel` + `bot_state`; entities, enums, repos; `Invitation`
  entity extended). See CLAUDE.md "Telegram Auth A".
- **Task B — bot worker** ✅ (RestClient long-poll loop, `/start` +
  contact-share + `/cancel` handlers, OTT mint/redeem service, locale-aware
  bot copy). All under `apps/api/.../identity/telegram/`. See CLAUDE.md
  "Telegram Auth B".
- **Task C — auth endpoints** ✅ `POST /api/v1/auth/telegram/accept`
  redeems the OTT → JWT (`TelegramAuthService` → `AuthService.issueSessionFor`;
  permit-all in `SecurityConfig`; 5-case `TelegramAcceptFlowIntegrationTest`).
  The widget login endpoint (`POST /api/v1/auth/telegram/login`, HMAC) is
  deferred to the domain-ready phase. See CLAUDE.md "Telegram Auth C".
- **Task D — frontend** ⬜ NEXT (`/auth/accept` page; admin invitation
  channel picker + funnel-state display; Login Widget button last).
- **Task E — integration test + dev runbook + env docs** ⬜ (mock
  Telegram at the HTTP level via the overridable `apiBaseUrl`).

## Build status

A and B were written in a sandbox without Gradle; **as of Task C they are
compile-, boot- and test-verified locally** (Flyway V012 applies,
Hibernate `validate` passes, Testcontainers suite green). Run Gradle with
Java 21 — the machine's default `java` is 1.8:

```
JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-21.0.6/Contents/Home ./gradlew :api:compileJava
```

## Blockers / needed from Bulat (for B/C/E runtime — not A)

- **Bot token + bot username** from `@BotFather`. Wire via env:
  `CHESSEDU_TELEGRAM_ENABLED=true`, `CHESSEDU_TELEGRAM_BOT_TOKEN=…`,
  `CHESSEDU_TELEGRAM_BOT_USERNAME=…` (see `.env.example`).
- **Prod domain** (or decision to stay localhost-only for now). Only the
  Login-Widget half of C/D needs it; the whole signup flow is testable
  without it.

## Task D — starting notes

Backend contract for the accept page: `POST /api/v1/auth/telegram/accept`
with `{ "ott": "<raw>" }` → `TokenResponse { accessToken, refreshToken,
tokenType: "Bearer", expiresIn }` — identical to what
`/auth/invitations/accept` returns, so the accept page can copy the
invitation accept page's token-storage + role-redirect tail verbatim.
Failures: `401 UNAUTHENTICATED` (unknown / used / expired OTT, or
suspended user — one opaque message), `400 VALIDATION_FAILED` (blank).
The page should read `?ott=` on mount, POST once, and on 401 show a
"link expired — ask for a new one in the bot" state with a link back to
`/auth/login` (email/password fallback stays).

Admin side: `invitation.delivery_channel` (`EMAIL` | `TELEGRAM`) and
`bot_state` (`PENDING` / `AWAITING_CONTACT` / `COMPLETED` / `CANCELLED`)
exist since Task A on the entity only — `InvitationService` /
`AdminInvitationController` and their DTOs do **not** expose
`deliveryChannel` / `botState` yet (grep confirms zero hits). Task D
therefore has a small backend part: accept `deliveryChannel` on issue
(default `EMAIL`; `TELEGRAM` skips the email send and sets
`bot_state=PENDING`), and surface both fields in the list view. Then the
issue form gets a channel picker and the list a funnel-state column.

## File anchors

- Telegram backend: `apps/api/src/main/java/com/chessedu/platform/identity/telegram/`
  (`TelegramAuthController` + `TelegramAuthService` = the Task C web half;
  test in `apps/api/src/test/.../identity/telegram/`).
- Existing auth to mirror: `.../identity/auth/AuthService.java`,
  `AuthController.java`; `.../identity/invitation/InvitationService.java`
  (the `accept` → auto-login path is the closest template for C).
- Config: `apps/api/src/main/resources/application.yml`
  (`chessedu.telegram.*`, kebab-case), `.env.example`.
- Auth UI (Task D): `apps/web/src/app/[locale]/auth/login/`,
  `apps/web/src/app/[locale]/auth/invitations/` (accept-page template).
- i18n: `apps/web/messages/{en,ru,uz}.json` (917 keys each; keep parity;
  Russian `one/few/many/other`, Uzbek `one/other`).

## Standing conventions (from CLAUDE.md, worth re-flagging)

- Append a `## Completed tasks` entry to CLAUDE.md (reverse-chronological)
  whenever a task completes, including a "How to check as a user" section.
- i18n parity across en/ru/uz on every change; verify by counting leaf keys.
- Jackson serves `non_null` — null fields drop on the wire and arrive as
  `undefined`; use loose `== null` checks on the client.
- `telegram_account` uses manual `linkedAt`/`lastSeenAt` timestamps (not
  `AuditingEntityListener`) — matches the local token-table convention;
  don't "correct" it.
- Bot worker + correlation map are **single-instance** (in-memory poll
  offset + `telegramId→invitationId` map). Multi-instance = Redis, later.
