# Kingside — Session handoff for Opus 4.8

## Codebase shape

Chess-education SaaS for Uzbek schools.

- **Backend**: Spring Boot (`apps/api`), Postgres + Flyway, Gradle. Migrations through `V011__block_question.sql`. JWT auth (access + refresh tokens). Role enums: `PLATFORM_ADMIN` / `SCHOOL_ADMIN` / `TEACHER` / `PARENT` / `STUDENT`. Students are profile-only rows (no credentials in v1).
- **Frontend**: Next.js 15 App Router, Turbopack, next-intl, React 19, `apps/web` workspace. Three locales: `uz` (default), `ru`, `en`. CSS in `globals.css` (custom design system, no Tailwind in product code). Locale at `/[locale]/...` URL prefix.
- **Roots**: `/admin/classes/[id]` (class detail — tab-based IA with roster/homework/lessons/schedule/qa), `/student/...`, `/parent/...`.
- **Demo seed accounts** on local profile: `admin@demo.local / admin12345`, `student@demo.local / student12345`.

## Standing conventions (read carefully)

- **CLAUDE.md is delivered via system-reminder** — never call Read on it. Append entries via Edit with the `## Completed tasks` anchor; entries go reverse-chronological at the top.
- **i18n parity**: every change touches en/ru/uz simultaneously. Russian uses `one/few/many/other` plurals; Uzbek uses `one/other`. Verify with a quick node walk counting leaf keys — they must match (currently 917 each).
- **Jackson serves `non_null`** — null fields strip on the wire and arrive as `undefined`. Use loose equality (`== null` / `!= null`) for nullability checks. Tripped #25.
- **React 19 strict mode bans setState in render**. Use `useEffect` with `cancelled = false` cleanup flag pattern. **CAREFUL**: don't include the in-progress flag (`loading` / `pending` / `detailLoading`) in the dep array OR a callback prop — both cause cleanup-mid-fetch race (cancelled flips true before the `.then` resolves → loading sticks forever, OR `Maximum update depth exceeded`). Comment with `// eslint-disable-next-line react-hooks/exhaustive-deps` + a CAREFUL docblock so the next agent doesn't undo it. Fixed across qa-inbox / qa-block-panel / block-qa-overlay / lesson-panels EditForm / homework-panels Submissions sheet & EditForm.
- **Router**: `useRouter` from `next/navigation` (Next native) accepts full paths with locale prefix; `useRouter` from `@/i18n/routing` (next-intl) auto-prepends locale. Don't mix them — produces `/en/en/...` 404s.
- **Build sandbox limitations**: no Gradle wrapper, no `javac`, no internet → backend code can't compile in the sandbox. Frontend `npx tsc --noEmit` works. User runs `./gradlew :api:compileJava` + `./gradlew :api:test --tests <Name>IntegrationTest` locally to verify backend slices.
- **Tools**: Edit (anchor-scoped, prefer narrow), Read (narrow line ranges to avoid autocompact thrashing). Don't Read CLAUDE.md.

## Recent completed umbrellas

- **Class Detail UX overhaul** (Tasks #60–#64): temporal buckets + sticky sub-nav, Overview hero, weekly calendar strip, tab-based IA (`?tab=roster|homework|lessons|schedule|qa`), day-selection filter.
- **Classroom-comms** (Tasks #65–#70, #73): block-level Q&A. V011 `block_question` + `block_question_reply` tables. Admin inbox tab, per-block overlay on Submissions sheet, student "Ask teacher" affordance on homework + lesson blocks, cross-class student rollup at `/student/questions`, sidebar question badge.
- **Lane B polish** (Tasks #71, #72, B3, B4 + sidebar badge): auto-mark `NOT_STARTED` on lesson open, walkthrough keyboard shortcuts (←/→/Home/End on tabIndex-0 article), teacher attempt history disclosure, cross-class student questions rollup.
- **Lane C authoring polish** (Tasks #45–#49): up/down reorder on lesson + homework block editors; Edit/Preview tabs on **all four block kinds** — PROSE (markdown via `@/components/prose-render`), VIDEO (iframe embed via `buildEmbedSrc`), MCQ (correct-answer-tinted static render), BOARD_WALKTHROUGH (extracted to `@/components/lesson-walkthrough-view` — shared with student page; takes a locale-agnostic `WalkthroughLabels` bag of 15 fields, two function-shaped for ICU interpolation).

## Current state

- **i18n**: en=ru=uz=917 keys.
- **CSS brace balance**: 1100/1100 in `apps/web/src/app/globals.css`.
- **Frontend typecheck**: clean.
- **Backend**: V011 latest migration. Q&A umbrella code not locally compile-verified by the sandbox — user has been running `./gradlew` locally.

## Active conversation — Telegram auth pivot

The user wants to add Telegram-based signup/signin for non-student roles. Confirmed answers:

1. **Bot registered**; they'll provide token + username.
2. They asked what "domain" means — I explained `@BotFather` → `/setdomain` registers the production hostname the Login Widget will accept auth from. One domain per bot, so prod + dev usually = two bots, OR use `/etc/hosts` + tunneling for dev.
3. **Telegram is the main flow** for new users; email/password stays as fallback only.
4. **Greenfield** — no existing accounts to migrate.
5. **All roles except STUDENT** (admins, teachers, parents).
6. **Invitation-only** signup. No self-signup.
7. **Phone capture is required**, via bot conversation (Telegram's native "Share Contact" keyboard button — phone is Telegram-verified, not user-typed).

I proposed Phase 1 (5 tasks A–E):

- **A** — V012 schema (`telegram_account`: user_id FK, telegram_id BIGINT unique, username, first_name, last_name, photo_url, phone E.164, language_code, chat_id, linked_at, last_seen_at). Extend `invitation` with `delivery_channel` + `bot_state`. Entities + repos.
- **B** — Bot worker. Long-polling via `@Scheduled` loop, raw HTTP (no library), handlers for `/start <token>` + contact-share + `/cancel`.
- **C** — Auth endpoints. `POST /api/v1/auth/telegram/accept` (redeems one-time token from bot→web handoff). `POST /api/v1/auth/telegram/login` (Login Widget HMAC-verified callback). `AuthService` extensions.
- **D** — Frontend. `/auth/login` adds widget button next to existing form; new `/auth/accept` page handles OTT redemption; admin invitation creation form picks Telegram-vs-email; invitation list shows bot funnel state. Locale parity.
- **E** — Integration test (mock Telegram at HTTP level) + dev runbook + env var docs.

### Two-flow split decided

- **Initial signup**: bot-driven (needs phone). `/start INV_<token>` → bot validates → "Share phone number" keyboard button → bot creates User + `telegram_account` + redeems invitation → bot replies with `/auth/accept?ott=<one-time-token>` deeplink → web redeems → JWT.
- **Repeat sign-in**: web Login Widget (faster, no bot DM needed). Same `telegram_account` row keyed by `telegram_id`. HMAC-verified against bot token.

### Three open questions I asked, awaiting answers

1. **Long-polling vs webhook?** I recommended long-polling for both dev and prod-v1 (zero infra, easy webhook conversion later).
2. **Raw HTTP vs `org.telegram:telegrambots-spring-boot-starter`?** I recommended raw HTTP (~150 LOC, no version coupling, we'd use ~10% of the library).
3. **Email/password fallback visibility on `/auth/login`?** I recommended keeping both forms visible (backstop for Telegram down / staff preference).

I also asked for the prod domain (or whether to start with localhost-only verification).

## Next concrete step

Awaiting answers on the three design questions + bot token/username/domain. Once received, start with **Task A** (V012 schema + JPA entities + repo + extending the invitation table). Backend code should mirror the LessonCompletion + BlockQuestion patterns line-for-line; AuditingEntityListener for timestamps, no `@GeneratedValue` (service sets explicit UUIDs).

**File anchors**:

- Existing auth: `apps/api/src/main/java/com/chessedu/platform/identity/` (User, Credentials, Membership, Invitation, RefreshToken, ParentLink + repos).
- Existing classes domain: `apps/api/src/main/java/com/chessedu/platform/classes/` (recent BlockQuestion work is the most up-to-date pattern reference).
- Auth UI: `apps/web/src/app/[locale]/auth/login/` (existing email/password form + actions.ts).
- i18n: `apps/web/messages/{en,ru,uz}.json`.

Reach for Edit + Read; resist re-reading large files; pin anchor strings tightly so edits stay surgical. Never Read CLAUDE.md.
