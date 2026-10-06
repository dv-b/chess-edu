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
- **Task D — frontend** ✅ `/<locale>/auth/accept` page (auto-submitting
  OTT redemption → cookies → role redirect); admin "Send via" picker,
  one-time deeplink card after a Telegram issue, Channel column + bot
  funnel note in the list. Backend: `deliveryChannel` on issue, channel /
  `botState` / `botDeeplink` on the view. See CLAUDE.md "Telegram Auth D".
  Login Widget button still deferred (prod domain).
- **Task E — integration test + dev runbook + env docs** ⬜ NEXT (mock
  Telegram at the HTTP level via the overridable `apiBaseUrl`; README
  still says the API is on 8080 — it's 7373 — and doesn't mention
  sourcing `.env` or the Java 21 requirement).

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

## Task E — starting notes

Everything from the bot's `/start` to the web redirect is now real code,
so E is about locking it down + making it runnable by someone new:

- **Mock-Telegram integration test.** Spin up a tiny HTTP server (JDK
  `com.sun.net.httpserver` or MockWebServer if you'd rather add a test
  dep) that answers `getUpdates` with a scripted `/start INV_<token>`
  then a contact message, and records `sendMessage` bodies. Point
  `chessedu.telegram.api-base-url` at it, `enabled=true`, and assert:
  invitation → `AWAITING_CONTACT` → `COMPLETED`, user + membership +
  `telegram_account` rows, the reply contains `/auth/accept?ott=`, and
  that OTT redeems via `POST /auth/telegram/accept`. `TelegramBotWorker`
  is `@Scheduled` — either call its poll method directly from the test
  or wait with Awaitility.
- **Dev runbook** (README or `docs/telegram-auth.md`): BotFather steps,
  the five `CHESSEDU_TELEGRAM_*` vars, `set -a; source .env; set +a`
  before `./gradlew :api:bootRun` (nothing loads `.env` automatically),
  Java 21 via `JAVA_HOME`, API port **7373**, web port 3100, Mailpit
  8125, and the "issue → copy deeplink → tap in Telegram → accept link"
  loop. Fix the README's 8080.
- **Env docs**: `.env.example` already documents the vars; add
  `WEB_BASE_URL` to it if missing (the bot builds the accept link from
  `chessedu.web.base-url`, default `http://localhost:3100`).

## File anchors

- Telegram backend: `apps/api/src/main/java/com/chessedu/platform/identity/telegram/`
  (`TelegramAuthController` + `TelegramAuthService` = the Task C web half;
  test in `apps/api/src/test/.../identity/telegram/`).
- Existing auth to mirror: `.../identity/auth/AuthService.java`,
  `AuthController.java`; `.../identity/invitation/InvitationService.java`
  (the `accept` → auto-login path is the closest template for C).
- Config: `apps/api/src/main/resources/application.yml`
  (`chessedu.telegram.*`, kebab-case), `.env.example`.
- Auth UI: `apps/web/src/app/[locale]/auth/accept/` (Telegram accept),
  `apps/web/src/app/[locale]/auth/login/`, `apps/web/src/lib/home-for-role.ts`.
- Admin invitations UI: `apps/web/src/app/[locale]/admin/invitations/`
  (`page.tsx` list, `new/invite-form.tsx` + `new/actions.ts`).
- i18n: `apps/web/messages/{en,ru,uz}.json` (946 keys each; keep parity;
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
