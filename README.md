# Chess Education Platform (codename: Kingside)

A multi-tenant B2B SaaS for chess schools. Each school is a tenant; inside the tenant, four roles live: **school admin**, **teacher**, **parent**, **student**.

> **Naming note.** The Java package, npm scopes, and repo name all use the generic `chessedu` / `chess-edu` placeholder. The product display name shown to users is **Kingside** for now — this is a placeholder too, to be finalised later.

## Repository layout

```
chess-edu-app/
├── apps/
│   ├── api/              # Spring Boot 3, Java 21, Gradle — REST API
│   └── web/              # Next.js 15, React 19, TypeScript — web frontend
├── packages/
│   ├── design-tokens/    # CSS custom properties (light + dark)
│   └── ui/               # Shared React primitives (Board, Piece, Icon…)
├── infra/
│   └── docker/           # Dockerfiles + local compose fragments
├── tools/                # Dev scripts (seed, openapi-gen, …)
├── docker-compose.yml    # Local dev: Postgres, Redis, MinIO, MailHog
├── pnpm-workspace.yaml
├── turbo.json
└── settings.gradle.kts
```

## Prerequisites

- **Java 21** (Temurin or similar)
- **Node.js 20+** and **pnpm 9+**
- **Docker** (for local Postgres, Redis, MinIO, MailHog)

## Run locally

Three shells, in order:

```bash
# 1) Local infra (Postgres :5433, Redis :6380, MinIO :9010/9011, Mailpit :2525/8125)
#    Host ports are shifted off the standard defaults to avoid colliding with
#    anything you've already got installed locally. Override via env:
#       POSTGRES_HOST_PORT=5434 docker compose up -d
docker compose up -d

# 2) Backend API — http://localhost:8080
cd apps/api
./gradlew bootRun

# 3) Frontend web — http://localhost:3000
pnpm install                    # from repo root
pnpm --filter web dev
```

Health-check: <http://localhost:8080/actuator/health>
Landing page: <http://localhost:3000> (redirects to `/uz`)

## Internationalization

Three locales ship at launch:

- **Uzbek (`uz`)** — default, Latin script
- **Russian (`ru`)**
- **English (`en`)**

Frontend strings live in `apps/web/messages/{uz,ru,en}/*.json` (ICU MessageFormat). Backend uses `MessageSource` only for email templates and error messages; API responses carry stable error codes, the client translates.

## What's in this first slice

- [x] Monorepo skeleton (pnpm + Turborepo + Gradle)
- [x] Local `docker-compose.yml` for Postgres / Redis / MinIO / MailHog
- [x] Spring Boot 3 app that boots, connects to Postgres, runs Flyway migrations
- [x] `School` entity (the tenant row) + Flyway baseline migration
- [x] `TenantContext` + `TenantFilter` stub (permissive until auth lands)
- [x] Global error envelope + request-id filter + actuator health
- [x] Next.js 15 app with `next-intl` 3-locale routing
- [x] Design tokens ported from the HTML prototype (light + dark)
- [x] Placeholder Kingside landing page + locale switcher
- [x] Placeholder login page

## What's next (per the Phase 4 plan)

M2 identity/auth, M3 roster + CSV import, admin screens (dashboard → students → teachers → classes → billing → settings), then teacher/student/parent screens, then homework assign + play + submission.

See the planning conversation for the full 6-milestone breakdown.
