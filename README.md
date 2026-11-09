# Anveshan — AI research and skill gap analysis platform

Give Anveshan a question or a goal. It researches it, writes a report where **every claim shows its source** (and says clearly when the evidence is weak), saves what it learned into your personal knowledge library, and — if the goal is a career skill — shows what you can already prove, what is missing, and what to learn next.

Every user brings their own AI key (Gemini, Groq, OpenRouter, OpenAI, Anthropic or a custom provider), so the platform stays free.

> **Status:** early development. Phases P00–P04 are done (accounts, project skeleton + CI, local Docker stack, embeddings, first database schema). See [Progress](#progress).

## What it does

| Area | What you get |
|---|---|
| Research | Plan you can edit before it runs → live progress → cited report |
| Trust labels | Each claim marked Supported / Weak evidence / Conflicting / No source found |
| Report Studio | Click a citation to see the exact source paragraph; edit, version, export (PDF, Markdown) |
| Ask the Report | Questions answered only from the report's sources |
| Knowledge Space | Concept notes created from every research run, linked together |
| Skill Proof Engine | Skill levels based on evidence (your GitHub code, project defense, challenges), not self-rating |
| Team | Shared workspace with a reviewer role for faculty |

## Tech stack

| Part | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Modulith (modular monolith) |
| Embeddings | multilingual-e5-small via ONNX Runtime (runs inside the backend, no external API) |
| Database | PostgreSQL 17 + pgvector, Flyway migrations |
| Frontend | Next.js 16 (App Router), TypeScript (strict), Tailwind CSS, shadcn/ui |
| Tests | JUnit 5, Testcontainers (real Postgres), Vitest, Playwright |
| CI | GitHub Actions |

## Project structure

```
apps/
├── api/    Spring Boot backend — one package per module under dev.anveshan
│           (identity, byok, research, sources, report, knowledge, skills,
│            team, notify, search, export, mcp, shared)
│           DB migrations: src/main/resources/db/migration/<module>/
└── web/    Next.js frontend
infra/
├── compose.yml      local stack: Postgres (pgvector) + API + Web
├── smoke-test.sh    checks a running stack
└── backup/run.sh    pg_dump backup with verify + 14-day rotation
.github/workflows/   api.yml, web.yml, stack.yml
```

## Run the whole stack (Docker only)

**Requirements:** Docker Desktop (running) and Git Bash (on Windows).

```bash
cd infra
cp .env.example .env               # set PG_PASSWORD (e.g. openssl rand -hex 24)
docker compose up -d --build --wait
bash smoke-test.sh                 # expect: ALL SMOKE CHECKS PASSED
```

| URL | What |
|---|---|
| http://localhost:3000 | Web app |
| http://localhost:8080/api/v1/health | Public health → `{"status":"UP"}` |

All ports are bound to `127.0.0.1` only; the actuator port (8081) is not published at all.

```bash
docker compose --profile backup run --rm backup   # backup → $BACKUP_DIR
API_TAG=<older-tag> docker compose up -d --no-build api   # roll back the API image
docker compose down                                 # stop (data kept); add -v to delete data
```

## Develop locally

**Requirements:** JDK 21, Node 24, pnpm 10, Docker Desktop (running).

```bash
# One-time: download the embedding model (~490 MB, checksum-verified, stored in ~/.cache/anveshan)
bash apps/api/scripts/fetch-model.sh

# Backend: build + all tests (starts a throwaway Postgres in Docker)
cd apps/api
./mvnw verify            # Windows: .\mvnw verify

# Backend: run the app against a throwaway Postgres container
./mvnw spring-boot:test-run

# Frontend
pnpm -C apps/web install
pnpm -C apps/web dev     # http://localhost:3000
pnpm -C apps/web typecheck && pnpm -C apps/web lint && pnpm -C apps/web test
```

Health check: `http://localhost:8081/actuator/health` → `{"status":"UP", ...}`

## Progress

| Phase | What | Status |
|---|---|---|
| P00 | Accounts and keys (OAuth apps, API keys, encryption keyset) | ✅ Done |
| P01 | Monorepo skeleton + CI | ✅ Done |
| P02 | Local Docker Compose stack, public health endpoint, backups | ✅ Done |
| P03 | Embeddings: multilingual-e5-small (ONNX, in-JVM, CPU) — ~12 chunks/s on 2 threads | ✅ Done |
| P04 | Database schema v1 (identity + BYOK), Flyway migrations | ✅ Done |
| P05 | Login with Google / GitHub | Next |
| P06 | Shared security (safe outbound HTTP, key encryption, log masking) | Planned |
| P07 | Bring-your-own-key backend | Planned |
| P08 | Frontend foundation + connect-your-AI wizard | Planned |

## Security

- User AI keys are encrypted at rest and never logged or returned (only the last 4 characters are shown).
- Every outbound request to a user-supplied URL goes through an SSRF guard.
- Secrets live only in environment variables, never in this repository.

Found a security issue? Please open a private security advisory on this repository instead of a public issue.

## Disclaimer

Reports are AI-assisted research drafts. Verify before submitting them anywhere.
