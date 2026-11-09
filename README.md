# Anveshan — AI research and skill gap analysis platform

Give Anveshan a question or a goal. It researches it, writes a report where **every claim shows its source** (and says clearly when the evidence is weak), saves what it learned into your personal knowledge library, and — if the goal is a career skill — shows what you can already prove, what is missing, and what to learn next.

Every user brings their own AI key (Gemini, Groq, OpenRouter, OpenAI, Anthropic or a custom provider), so the platform stays free.

> **Status:** early development. Phase P01 (project skeleton + CI) is done. See [Progress](#progress).

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
└── web/    Next.js frontend
.github/workflows/   api.yml, web.yml
```

## Run locally

**Requirements:** JDK 21, Node 24, pnpm 10, Docker Desktop (running).

```bash
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
| P00 | Accounts and keys (OAuth apps, API keys, encryption keyset) | In progress |
| P01 | Monorepo skeleton + CI | ✅ Done |
| P02 | Local Docker Compose stack | Next |
| P03 | Embedding model smoke test | Planned |
| P04 | Database schema v1 (identity + BYOK) | Planned |
| P05 | Login with Google / GitHub | Planned |
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
