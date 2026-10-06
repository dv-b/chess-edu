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
- **Task C — auth endpoints** ⬜ NEXT. `POST /api/v1/auth/telegram/accept`
  redeems the OTT → JWT. The widget login endpoint
  (`POST /api/v1/auth/telegram/login`, HMAC) is deferred to the
  domain-ready phase.
- **Task D — frontend** ⬜ (`/auth/accept` page; admin invitation
  channel picker + funnel-state display; Login Widget button last).
- **Task E — integration test + dev runbook + env docs** ⬜ (mock
  Telegram at the HTTP level via the overridable `apiBaseUrl`).

## ⚠️ Not compile-verified

A and B were written in Cowork's sandbox, which has **no Gradle / javac /
internet**, so the backend has never been compiled. Before building on it:

```
./gradlew :api:compileJava
```

Entity/repo references were checked by hand against the real sources
(`UserRepository.findByEmail`, `Membership` setters,
`TelegramAccountRepository.existsByTelegramId`, `ErrorCode.UNAUTHENTICATED`
— there is no `INVALID_TOKEN` code). Boot once and confirm Flyway applies
V012, then Hibernate `validate` maps the new entities cleanly.

## Blockers / needed from Bulat (for B/C/E runtime — not A)

- **Bot token + bot username** from `@BotFather`. Wire via env:
  `CHESSEDU_TELEGRAM_ENABLED=true`, `CHESSEDU_TELEGRAM_BOT_TOKEN=…`,
  `CHESSEDU_TELEGRAM_BOT_USERNAME=…` (see `.env.example`).
- **Prod domain** (or decision to stay localhost-only for now). Only the
  Login-Widget half of C/D needs it; the whole signup flow is testable
  without it.

## Task C — starting notes

`TelegramAuthTokenService.redeem(rawToken)` already exists and returns the
`User`, throwing `AppException(UNAUTHENTICATED)` on any failure. So the
accept controller is thin:

- New `POST /api/v1/auth/telegram/accept` (public, no auth) taking
  `{ ott }` → `redeem` → mint the JWT pair via the existing `AuthService`
  token machinery, same shape `InvitationService.accept` returns
  (`TokenResponse`). Reuse `AuthService.mintTokens` (it's private — either
  add a package-private `issueTokensFor(User, http)` to `AuthService` or
  mirror the ~12 lines, matching how `InvitationService` auto-logs-in).
- Needs an IP/User-Agent/`HttpServletRequest` for the refresh-token row,
  like the other auth endpoints.
- Security: the accept endpoint must be on the permit-all list in
  `SecurityConfig` (same as `/auth/login`, `/auth/invitations/**`).

## File anchors

- Telegram backend: `apps/api/src/main/java/com/chessedu/platform/identity/telegram/`
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
