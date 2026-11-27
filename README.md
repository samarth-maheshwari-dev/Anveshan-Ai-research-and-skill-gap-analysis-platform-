# Anveshan — AI research and skill gap analysis platform

Give Anveshan a question or a goal. It researches it, writes a report where **every claim shows its source** (and says clearly when the evidence is weak), saves what it learned into your personal knowledge library, and — if the goal is a career skill — shows what you can already prove, what is missing, and what to learn next.

Users bring no AI key: the platform calls free AI endpoints through one built-in gateway (freellmapi first, Gemini as fallback), with a small daily research limit per user, so it stays free.

> **Status:** early development. Phases P00–P16 are done (P16 = built-in AI gateway, no user keys), and the report export part of P16b is in: sign in, start a research, review and approve the plan, watch it search and read sources, extract, verify and label claims, get a written report with numbered citations, edit it with the exact source passage behind every citation, and download it as PDF or Markdown. P16c added a redesigned, plain-language interface, charts and a report map on every report, and faster, richer reports. See [Progress](#progress).

## What works today

- **Sign in** with Google or GitHub.
- **Simple to use the first time.** The landing page explains the product in three steps as you scroll; the app menu shows only screens that work, with "Skill gap analysis" marked as coming soon.
- **No key needed.** A "Service status" card shows whether the built-in AI is ready or busy, and how many researches you have left today (5 per day).
- **Start a research** in 4 steps: topic (optionally from a template), objective, sources, review. Every choice has a "your own" option — your own objective, depth (sub-questions × sources), report format, report sections, websites to prefer or avoid, a start year, and extra instructions.
- **Review the plan before anything is spent.** The AI splits your question into 1–8 sub-questions. Edit, reorder, delete or add them, or ask for a new plan ("focus on India"). The estimate — AI requests per task and minutes — updates as you edit.
- **Search.** After approval each sub-question is searched across up to 8 free sources at once — research papers, preprints, Wikipedia, GitHub, the web — then de-duplicated and ranked on your machine (no AI request). A source that is down is skipped and noted. Papers their journal retracted (OpenAlex, and Crossref's Retraction Watch data) are left out before anything reads them, and the live log names them.
- **Read.** The best results are opened and read: main text only (no menus, ads or scripts), PDFs page by page, sites that opt out via robots.txt are skipped. Each source gets a credibility tier with a reason (peer-reviewed paper, official docs, preprint, blog …), and the passages most relevant to each sub-question are picked — all locally, no AI request.
- **Check.** Each passage becomes short factual claims, each tied to the exact sentence it came from (located by code, never trusted from the AI). Every claim is checked against other sources and labelled by fixed rules: Supported, Weak evidence, Conflicting or No source. A claim is Supported only when a credible source *other than the one it came from* backs it. A batch too large for the AI model is checked in halves instead of stalling the run.
- **Write.** Each report section is written only from Supported and Weak-evidence claims; every sentence carries a citation that code checks (unknown ones are removed). Citations are numbered by first appearance, references are formatted in IEEE, and a Conflicts section lists claims the sources disagree on — built by code, with a one-line reason from the AI.
- **Read the report.** A clean report page with numbered citations, trust labels (icon + word), an evidence summary, open questions (claims no source could confirm) and references. Your dashboard lists every research with its status.
- **See the report at a glance.** Key findings, an evidence donut, the sources used most, source types and years, and a report map (your question → each section, with its citation count). Every number is counted by code from the report — never decided by the AI. Sections have short sub-headings and a "Key point" callout.
- **Report Studio.** Edit any section in a rich-text editor; citations stay attached to their claims and renumber themselves. Click a citation to see the exact passage it came from, highlighted, and which other sources were checked. Ask the AI to simplify, add detail or translate to Hindi a selected passage, regenerate a section from its own claims, or "find better sources" for a weak claim. Every save is a version you can restore.
- **Download the report** as PDF (A4, page numbers, Hindi rendered with a Devanagari font) or Markdown, with its citations, references and the AI-draft disclaimer. Only the report's owner can download it.
- **Live Room.** Watch a research as it happens: steps and progress, sources as they are found, conflicts, and a live log. Close the tab and come back later — it kept going and the log is still there.
- **Skip a source** you don't trust while the research runs (pause, skip, resume). It is gone for good: never cited, its claims never written, labels recomputed without it, and a section already written from it is written again.
- **Approve and watch it run.** Runs are durable: pause, resume or stop them, and they continue where they left off after a crash or an AI rate limit. A busy AI provider is retried; if it still fails you get a plain explanation and a "Start again" button.

## What it does (full plan)

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
| Frontend | Next.js 16 (App Router), TypeScript (strict), Tailwind CSS v4, shadcn/ui components (owned copies), TanStack Query |
| Tests | JUnit 5, Testcontainers (real Postgres), Vitest, Playwright |
| CI | GitHub Actions |

## Project structure

```
apps/
├── api/    Spring Boot backend — one package per module under dev.anveshan
│           (identity, llm, research, sources, report, knowledge, skills,
│            team, notify, search, export, mcp, shared)
│           DB migrations: src/main/resources/db/migration/<module>/
└── web/    Next.js frontend
infra/
├── compose.yml      local stack: Postgres (pgvector) + API + Web + SearXNG (internal web search)
├── smoke-test.sh    checks a running stack
└── backup/run.sh    pg_dump backup with verify + 14-day rotation
.github/workflows/   api.yml, web.yml, stack.yml
```

## Run the whole stack (Docker only)

**Requirements:** Docker Desktop (running) and Git Bash (on Windows).

```bash
cd infra
cp .env.example .env               # set PG_PASSWORD (openssl rand -hex 24) and OAUTH_ENV_FILE
# In the file OAUTH_ENV_FILE points to (outside the repo) add GEMINI_API_KEY, TAVILY_API_KEY and optionally
# GROQ_API_KEY, FREELLMAPI_KEY, SEMANTIC_SCHOLAR_API_KEY, OPENALEX_API_KEY. A blank key = that service is skipped.
# LLM order: Gemini → Groq → freellmapi (fastest first).
# Run freellmapi on the host (port 3001); the API reaches it at host.docker.internal:3001.
docker compose up -d --build --wait
bash smoke-test.sh                 # expect: ALL SMOKE CHECKS PASSED
```

| URL | What |
|---|---|
| http://localhost:3000 | Web app |
| http://localhost:8080/api/v1/health | Public health → `{"status":"UP"}` |

All ports are bound to `127.0.0.1` only; the actuator port (8081) is not published at all.

**Sign in locally (optional).** Create Google and GitHub OAuth apps with redirect URIs
`http://localhost:8080/login/oauth2/code/google` and `http://localhost:8080/login/oauth2/code/github`,
put `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` in an env file
**outside the repo**, and set `OAUTH_ENV_FILE` to its path in `infra/.env`. Then open
http://localhost:8080/oauth2/authorization/google and afterwards http://localhost:8080/api/v1/me.

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
| P04 | Database schema v1 (identity + AI keys; key tables removed in P16), Flyway migrations | ✅ Done |
| P05 | Login with Google / GitHub, server-side sessions, CSRF, CORS, login rate limit | ✅ Done |
| P06 | Shared security: SSRF-safe HTTP client, Tink encryption for user keys (removed in P16), log masking | ✅ Done |
| P07 | Bring-your-own-key backend: connections, model lists, task routing, LLM gateway with rate-limit fallback | ✅ Done |
| P08 | Frontend foundation + connect-your-AI wizard: black-first 60·30·10 design, landing, login, wizard, dashboard, Settings → AI; key test that picks a working model and survives busy providers | ✅ Done |
| P09 | Research run engine: state machine, durable background steps, pause / resume / stop / retry, live events (SSE) with replay, crash recovery | ✅ Done |
| P10 | New Research form (templates + custom options), AI planner, Plan Review (edit, regenerate, live estimate, approve), unapproved plans expire after 7 days | ✅ Done |
| P11 | Search: 8 free sources in parallel (OpenAlex, Semantic Scholar, arXiv, Crossref, Wikipedia, GitHub, your Tavily key, a built-in SearXNG), de-duplicated and ranked locally; one source down never stops a run | ✅ Done |
| P12 | Read sources: robots.txt respected, web pages cleaned to their main text, PDFs read page by page, split into passages and embedded locally, credibility rated with a reason; public pages shared across runs, your own files kept private | ✅ Done |
| P13 | Claims: atomic facts extracted with an exact quote whose position is found by code (exact / normalized / fuzzy), checked against other sources, trust-labelled by rules (Supported / Weak / Conflicting / No source); claims reused across runs; instructions hidden in web pages are ignored | ✅ Done |
| P14 | Report: sections written from verified claims only, code-checked citation markers, numbering by first appearance, IEEE/APA references, Conflicts section by code; Live Room (steps, sources with Skip, conflicts, live log that survives closing the tab); read-only report page; research list on the dashboard | ✅ Done |
| P15 | Report Studio: rich-text editing with live citation renumbering, citation panel with the exact passage, versions + restore, AI assist, regenerate, find better sources; independent-source labelling, open questions; skipped sources fully removed from the report; retracted papers left out at search | ✅ Done |
| P16 | Built-in AI gateway: users bring no keys; freellmapi → Gemini fallback with circuit breaker, daily run limit, AI status card; cleaner references, relevant open questions, more systematic writing | ✅ Done |
| P16b | Export: PDF + Markdown download ✅; background export jobs, expiring links, golden-set evaluation | In progress |
| P16c | Redesigned interface (black/white/green, scroll story, plain language); report charts + report map; faster runs (lighter fetch, shorter timeouts, Gemini → Groq first); richer reports (extraction in batches of 5, writer v4 with sub-headings and key points); search keys read under both names | ✅ Done |
| P17 | Ask the Report | Next |
| P18–P30 | Ask the Report, Sources Library, Knowledge Space, Skill Proof Engine, team workspace, release | Planned |

## Security

- Sign-in only through Google / GitHub; sessions live server-side (HttpOnly cookie), CSRF and strict CORS on every API call.
- Accounts are linked by email only when the provider has verified it (no account takeover via unverified emails).
- Users never handle AI keys. The developer's keys live only in an env file outside the repo, are never logged or returned (config prints `****`), and provider error messages are never passed through, since they can echo a key.
- Every LLM call goes through one gateway: the reply must parse into a typed record and pass validation (max 3 tries), and a rate-limited or failing endpoint falls back to the next one (freellmapi → Gemini), and a retry after an unusable answer goes to a different endpoint first; an endpoint that fails 3 times is skipped for 60 s, and each user gets a daily research limit. Each attempt is logged with counts and timings only (never prompts or keys).
- Text from web pages is wrapped as untrusted data in prompts; an instruction hidden in a page ("ignore previous instructions…") can't add a claim, because every claim must quote the page exactly and stay about that quote.
- What a user types (topic, instructions, section names) is treated as data in AI prompts: it is wrapped in tags and cannot close them, so it can't take over the prompt. AI output (like a plan) is checked by code before it is saved.
- Plan edits and approval carry the version the user saw (`If-Match`); a change made elsewhere meanwhile is never silently overwritten. Starting research and asking for new plans are rate-limited per user.
- Every outbound request to a user-supplied URL goes through an SSRF guard: private/loopback/metadata IPs and odd IP spellings are refused, DNS answers are checked at connect time (no rebinding), redirects are re-checked (max 3) and bodies are size-capped.
- Credential-like values (API keys, tokens, `Bearer …`) are masked in every log line and stack trace; CI fails if one shows up in container logs.
- Secrets live only in environment variables, never in this repository.

Found a security issue? Please open a private security advisory on this repository instead of a public issue.

## Disclaimer

Reports are AI-assisted research drafts. Verify before submitting them anywhere.
