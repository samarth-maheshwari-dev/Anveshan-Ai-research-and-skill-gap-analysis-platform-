import type { BrowserContext, Page, Route } from "@playwright/test";

import { estimate, sizeOf, type Depth } from "../lib/estimate";

/**
 * An in-memory stand-in for the Anveshan API (http://localhost:8080), installed per test with
 * page.route. It behaves like the real backend where the UI depends on it: session cookie, CSRF header on
 * writes, 401 without a session, the AI status card and the daily research limit.
 */
export const API = "http://localhost:8080";
export const APP = "http://localhost:3100";
export type AiStatusView = { state: "ONLINE" | "BUSY" | "OFFLINE"; runsUsedToday: number; runsLimit: number };

export type FakeState = {
  signedIn: boolean;
  aiStatus: AiStatusView;
  writesWithoutCsrf: string[];
  calls: string[];
  runs: FakeRun[];
  /** Idempotency-Key of every POST /runs. */
  runKeys: string[];
  /** Body of every POST /runs. */
  runBodies: Record<string, unknown>[];
  /** Last-Event-ID of every events request (0 = from the start). */
  eventRequests: number[];
  skipped: string[];
  actions: string[];
  /** Instruction of every POST …/plan/regenerate. */
  regenerateInstructions: string[];
  /** Section saves: the section and the If-Match each PATCH carried. */
  sectionSaves: { sectionId: string; ifMatch: string | null; contentDoc: unknown }[];
};

export type FakeRun = {
  id: string;
  status: "PLANNING" | "AWAITING_APPROVAL" | "RUNNING" | "PAUSED" | "COMPLETED" | "FAILED" | "CANCELLED";
  topic: string;
  config: { depth: Depth; sources: string[]; templateCode?: string; sections?: string[]; subQuestions?: number; sourcesPerQuestion?: number };
  subQuestions: { id: string; position: number; text: string; sourceTypes: string[]; queries: string[] }[];
  version: number;
  /** GETs left before the fake Planner finishes (PLANNING → AWAITING_APPROVAL). */
  planningPolls: number;
  /** The PLAN step failed with this message (status FAILED). */
  planError?: string;
  /** S-07: the run's sources (USED / REJECTED_BY_USER). */
  sources?: FakeSource[];
  /** SSE events, replayed after Last-Event-ID. */
  events?: { id: number; type: string; data: Record<string, unknown> }[];
  /** Resuming this run finishes it with a report (lets E2E reach "Report ready"). */
  completeOnResume?: boolean;
  reportId?: string;
  /** S-08: the report as the fake API keeps it (created on first read, changed by PATCH). */
  report?: FakeReport;
};

type FakeReport = ReturnType<typeof fakeReport>;

export type FakeSource = {
  sourceId: string;
  title: string;
  site: string;
  url: string;
  type: string;
  tier: "HIGH" | "MEDIUM" | "LOW";
  tierReason: string;
  status: "USED" | "REJECTED_BY_USER";
  claims: number;
};

export const TEMPLATES = [
  {
    code: "LITERATURE_REVIEW",
    name: "Literature review",
    formDefaults: { objective: "LEARNING", depth: "DEEP", sources: ["ACADEMIC", "WEB"], outputStyle: "PROJECT_REPORT" },
    sections: ["Introduction", "Background", "Themes in the literature", "Methods used", "Gaps and open questions", "Conclusion"],
  },
  {
    code: "COMPETITOR_ANALYSIS",
    name: "Competitor analysis",
    formDefaults: { objective: "COMPARISON", depth: "STANDARD", sources: ["WEB", "BLOGS"], outputStyle: "COMPARISON_TABLE" },
    sections: ["Market", "Competitors", "Features", "Pricing", "Verdict"],
  },
  {
    code: "TECH_COMPARISON",
    name: "Tech comparison",
    formDefaults: { objective: "COMPARISON", depth: "STANDARD", sources: ["DOCS", "GITHUB", "WEB"], outputStyle: "COMPARISON_TABLE" },
    sections: ["Context", "Options", "Trade-offs", "Benchmarks", "Recommendation"],
  },
  {
    code: "CAREER_ROADMAP",
    name: "Career roadmap",
    formDefaults: { objective: "LEARNING", depth: "STANDARD", sources: ["WEB", "BLOGS"], outputStyle: "STUDY_NOTES" },
    sections: ["Role", "Skills", "Roadmap", "Resources", "Next steps"],
  },
];

export function fakeRun(overrides: Partial<FakeRun> = {}): FakeRun {
  return {
    id: "r-existing",
    status: "AWAITING_APPROVAL",
    topic: "Compare lost-and-found apps for college campuses",
    config: { depth: "STANDARD", sources: ["WEB", "ACADEMIC"] },
    subQuestions: [1, 2, 3, 4, 5].map((i) => ({
      id: `sq${i}`,
      position: i - 1,
      text: `Which campus apps solve problem ${i}?`,
      sourceTypes: ["WEB"],
      queries: [`campus lost and found ${i}`],
    })),
    version: 2,
    planningPolls: 0,
    ...overrides,
  };
}

export async function installFakeApi(page: Page, context: BrowserContext, init: Partial<FakeState> = {}) {
  const state: FakeState = {
    signedIn: false,
    aiStatus: { state: "ONLINE", runsUsedToday: 0, runsLimit: 5 },
    writesWithoutCsrf: [],
    calls: [],
    runs: [],
    runKeys: [],
    runBodies: [],
    eventRequests: [],
    skipped: [],
    actions: [],
    regenerateInstructions: [],
    sectionSaves: [],
    ...init,
  };
  let seq = 0;

  const signIn = async () => {
    state.signedIn = true;
    await context.addCookies([
      { name: "ANV_SESSION", value: "fake-session", domain: "localhost", path: "/", httpOnly: true, sameSite: "Lax" },
      { name: "XSRF-TOKEN", value: "csrf-token-123", domain: "localhost", path: "/", sameSite: "Lax" },
    ]);
  };
  if (state.signedIn) await signIn();

  const cors = {
    "access-control-allow-origin": APP,
    "access-control-allow-credentials": "true",
    "access-control-expose-headers": "ETag,Location",
  };
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, headers: { ...cors, "content-type": "application/json" }, body: JSON.stringify(body) });
  const problem = (route: Route, status: number, code: string, detail: string, reason?: string) =>
    route.fulfill({
      status,
      headers: { ...cors, "content-type": "application/problem+json" },
      body: JSON.stringify({ status, code, title: "Error", detail, ...(reason ? { reason } : {}) }),
    });
  // OAuth start: the real API redirects to Google; here "Google" approves at once and comes back.
  await context.route(`${API}/oauth2/authorization/**`, async (route) => {
    state.calls.push("oauth");
    await signIn();
    await route.fulfill({ status: 302, headers: { location: `${APP}/dashboard` } });
  });

  await context.route(`${API}/api/v1/ai/status`, async (route) => {
    if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers: cors });
    state.calls.push("GET /ai/status");
    if (!state.signedIn) return problem(route, 401, "NOT_AUTHENTICATED", "Please sign in");
    return json(route, 200, state.aiStatus);
  });

  await context.route(`${API}/api/v1/**`, async (route) => {
    const req = route.request();
    const method = req.method();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    if (path === "/ai/status") return route.fallback(); // handled by the dedicated route above
    if (method === "OPTIONS") {
      return route.fulfill({
        status: 204,
        headers: { ...cors, "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE", "access-control-allow-headers": "Content-Type,X-XSRF-TOKEN,Idempotency-Key,If-Match" },
      });
    }
    state.calls.push(`${method} ${path}`);
    if (path === "/templates" && method === "GET") return json(route, 200, { items: TEMPLATES });
    if (!state.signedIn) return problem(route, 401, "NOT_AUTHENTICATED", "Please sign in");
    if (method !== "GET" && (await req.headerValue("x-xsrf-token")) !== "csrf-token-123") {
      state.writesWithoutCsrf.push(`${method} ${path}`);
      return problem(route, 403, "FORBIDDEN", "Refused");
    }

    if (path === "/me") {
      return json(route, 200, {
        id: "u1",
        email: "riya@example.com",
        name: "Riya Sharma",
        locale: "en",
        timezone: "Asia/Kolkata",
        identities: ["GOOGLE"],
        onboarding: { hasRun: false },
      });
    }
    if (path === "/auth/logout" && method === "POST") {
      state.signedIn = false;
      await context.clearCookies();
      return route.fulfill({ status: 204, headers: cors });
    }
    if (path === "/runs" && method === "POST") {
      const body = req.postDataJSON() as {
        topic: string;
        depth?: Depth;
        sources?: string[];
        templateCode?: string;
        sections?: string[];
        subQuestions?: number;
        sourcesPerQuestion?: number;
      };
      state.runBodies.push(body);
      state.runKeys.push((await req.headerValue("idempotency-key")) ?? "");
      if (state.aiStatus.runsUsedToday >= state.aiStatus.runsLimit) {
        return problem(route, 429, "LLM_DAILY_LIMIT", "Daily research limit reached");
      }
      const run = fakeRun({
        id: `r${++seq}`,
        status: "PLANNING",
        topic: body.topic,
        config: {
          depth: body.depth ?? "STANDARD",
          sources: body.sources ?? ["WEB"],
          templateCode: body.templateCode,
          sections: body.sections,
          subQuestions: body.subQuestions,
          sourcesPerQuestion: body.sourcesPerQuestion,
        },
        version: 0,
        planningPolls: 1,
      });
      state.runs.push(run);
      return json(route, 202, runView(run));
    }
    if (path === "/runs" && method === "GET") {
      return json(route, 200, { items: [...state.runs].reverse().map(runSummary), nextCursor: null });
    }
    const reportPath = path.match(/^\/reports\/([^/]+)(?:\/(claims)\/([^/]+)\/passage|\/(sections)\/([^/]+)|\/(versions))?$/);
    if (reportPath) {
      const run = state.runs.find((r) => r.reportId === reportPath[1]);
      if (!run) return problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
      const report = (run.report ??= fakeReport(run));
      if (reportPath[2] && method === "GET") {
        const p = PASSAGES[reportPath[3]];
        return p ? json(route, 200, p) : problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
      }
      if (reportPath[4] && method === "PATCH") {
        const section = report.sections.find((x) => x.id === reportPath[5]);
        if (!section) return problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
        const ifMatch = (await req.headerValue("if-match")) ?? null;
        const body = req.postDataJSON() as { contentDoc: (typeof section)["contentDoc"] };
        state.sectionSaves.push({ sectionId: section.id, ifMatch, contentDoc: body.contentDoc });
        if (ifMatch !== `"${section.version}"`) {
          return route.fulfill({
            status: 409,
            headers: { ...cors, "content-type": "application/problem+json" },
            body: JSON.stringify({ status: 409, code: "VERSION_CONFLICT", title: "Changed meanwhile", detail: "Reload", currentVersion: String(section.version) }),
          });
        }
        section.contentDoc = body.contentDoc;
        section.version += 1;
        report.versions.unshift({ version: report.versions.length + 1, createdAt: "2026-11-17T06:00:00Z", byYou: true });
        return route.fulfill({
          status: 200,
          headers: { ...cors, "content-type": "application/json", etag: `"${section.version}"` },
          body: JSON.stringify(section),
        });
      }
      if (reportPath[6] && method === "GET") return json(route, 200, { items: report.versions, nextCursor: null });
      if (method === "GET") return json(route, 200, report);
    }
    const runExtra = path.match(/^\/runs\/([^/]+)\/(events|sources)(?:\/([^/]+)\/skip)?$/);
    if (runExtra) {
      const run = state.runs.find((r) => r.id === runExtra[1]);
      if (!run) return problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
      if (runExtra[2] === "events") {
        const after = Number((await req.headerValue("last-event-id")) ?? "0") || 0;
        const body = ["retry: 600000", ""]
          .concat((run.events ?? []).filter((e) => e.id > after)
            .flatMap((e) => [`id: ${e.id}`, `event: ${e.type}`, `data: ${JSON.stringify(e.data)}`, ""]))
          .join("\n") + "\n";
        state.eventRequests.push(after);
        return route.fulfill({
          status: 200,
          headers: { ...cors, "content-type": "text/event-stream", "cache-control": "no-store" },
          body,
        });
      }
      if (runExtra[3] && method === "POST") {
        const src = run.sources?.find((x) => x.sourceId === runExtra[3]);
        if (!src) return problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
        src.status = "REJECTED_BY_USER";
        state.skipped.push(src.sourceId);
        addEvent(run, "source.skipped", { sourceId: src.sourceId });
        return route.fulfill({ status: 204, headers: cors });
      }
      return json(route, 200, run.sources ?? []);
    }
    const runPath = path.match(/^\/runs\/([^/]+)(\/plan\/regenerate|\/plan|\/approve|\/pause|\/resume|\/cancel)?$/);
    if (runPath) {
      const run = state.runs.find((r) => r.id === runPath[1]);
      if (!run) return problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
      const sub = runPath[2];
      const withEtag = (status: number) =>
        route.fulfill({
          status,
          headers: { ...cors, "content-type": "application/json", etag: `"${run.version}"` },
          body: JSON.stringify(runView(run)),
        });
      if (!sub && method === "DELETE") {
        if (run.status === "RUNNING" || run.status === "PLANNING") {
          return problem(route, 409, "RUN_INVALID_STATE", "Stop the research first");
        }
        state.runs.splice(state.runs.indexOf(run), 1);
        return route.fulfill({ status: 204, headers: cors });
      }
      if (!sub && method === "GET") {
        if (run.status === "PLANNING" && run.planningPolls-- <= 0) {
          run.status = "AWAITING_APPROVAL";
          run.version += 1;
        }
        return withEtag(200);
      }
      const ifMatch = Number(((await req.headerValue("if-match")) ?? "").replaceAll("\"", ""));
      if (sub === "/plan" || sub === "/approve" || sub === "/plan/regenerate") {
        if (run.status !== "AWAITING_APPROVAL") return problem(route, 409, "RUN_INVALID_STATE", "Plan is locked");
        if (ifMatch !== run.version) {
          return route.fulfill({
            status: 409,
            headers: { ...cors, "content-type": "application/problem+json" },
            body: JSON.stringify({ status: 409, code: "VERSION_CONFLICT", detail: "Changed meanwhile", currentVersion: run.version }),
          });
        }
      }
      if (sub === "/plan" && method === "PUT") {
        const cards = (req.postDataJSON() as { subQuestions: { id?: string; text: string; sourceTypes?: string[] }[] })
          .subQuestions;
        if (cards.length < 1 || cards.length > 8) return problem(route, 400, "VALIDATION_FAILED", "A plan has 1–8 sub-questions.");
        run.subQuestions = cards.map((c, i) => ({
          id: c.id ?? `sq-new-${++seq}`,
          position: i,
          text: c.text,
          sourceTypes: c.sourceTypes?.length ? c.sourceTypes : run.config.sources,
          queries: [c.text],
        }));
        run.version += 1;
        return withEtag(200);
      }
      if (sub === "/plan/regenerate" && method === "POST") {
        const instruction = (req.postDataJSON() as { instruction?: string } | null)?.instruction ?? "";
        state.regenerateInstructions.push(instruction);
        run.subQuestions = [1, 2, 3].map((i) => ({
          id: `sq-regen-${++seq}`,
          position: i - 1,
          text: `New angle ${i}: ${instruction || "different focus"}`,
          sourceTypes: ["WEB"],
          queries: [`new angle ${i}`],
        }));
        run.version += 1;
        return withEtag(200);
      }
      if (sub === "/approve" && method === "POST") {
        run.status = "RUNNING";
        run.version += 1;
        return route.fulfill({ status: 202, headers: cors });
      }
      if (sub && method === "POST") {
        run.status = sub === "/pause" ? "PAUSED" : sub === "/resume" ? "RUNNING" : "CANCELLED";
        run.version += 1;
        state.actions.push(sub.slice(1));
        addEvent(run, "run.status", { status: run.status });
        if (sub === "/resume" && run.completeOnResume) {
          run.status = "COMPLETED";
          run.reportId = `rep-${run.id}`;
          addEvent(run, "log", { level: "INFO", message: "Report ready: 5 sections, 3 citations from 2 sources." });
          addEvent(run, "report.ready", { reportId: run.reportId });
          addEvent(run, "run.status", { status: "COMPLETED" });
        }
        return route.fulfill({ status: 202, headers: cors });
      }
    }
    return problem(route, 404, "NOT_FOUND", `No fake for ${method} ${path}`);
  });

  return state;
}

/** RunView as the API sends it; the estimate mirrors Estimate.of (sections: the template's, or 5). */
function addEvent(run: FakeRun, type: string, data: Record<string, unknown>) {
  run.events = run.events ?? [];
  run.events.push({ id: (run.events.at(-1)?.id ?? 0) + 1, type, data });
}

function runSummary(run: FakeRun) {
  return {
    id: run.id,
    topic: run.topic,
    status: run.status,
    progress: run.status === "COMPLETED" ? 100 : run.status === "RUNNING" ? 40 : 0,
    createdAt: "2026-11-11T06:00:00Z",
    updatedAt: "2026-11-11T06:00:00Z",
    reportId: run.reportId ?? null,
  };
}

/** A small report in the API's shape: two sections citing two sources, conflicts, references. */
function fakeReport(run: FakeRun) {
  const cite = (claimId: string, sourceId: string, n: number, label: string) => ({
    type: "citation",
    attrs: { claimId, sourceId, n, label },
  });
  const para = (...content: unknown[]) => ({ type: "paragraph", content });
  const text = (t: string) => ({ type: "text", text: t });
  return {
    id: run.reportId,
    runId: run.id,
    title: run.topic,
    language: "en",
    citationStyle: "IEEE",
    createdAt: "2026-11-12T06:00:00Z",
    sections: [
      { id: "s1", key: "introduction", title: "Introduction", version: 0,
        contentDoc: { type: "doc", content: [para(text("Students report lost items from their phones."), cite("cl1", "src1", 1, "SUPPORTED"))] } },
      { id: "s2", key: "key_findings", title: "Key findings", version: 0,
        contentDoc: { type: "doc", content: [para(text("Some apps return about 40 percent of items."), cite("cl2", "src2", 2, "WEAK"))] } },
      { id: "s3", key: "conflicts", title: "Conflicts & open questions", version: 0,
        contentDoc: { type: "doc", content: [para(text("No conflicting evidence was found between the sources."))] } },
      { id: "s4", key: "references", title: "References", version: 0,
        contentDoc: { type: "doc", content: [para(text("[1] \"Lost and found apps compared,\" campus.example, 2025.")), para(text("[2] \"Item recovery study,\" study.example, 2024."))] } },
    ],
    citations: [{ n: 1, claimId: "cl1", sourceId: "src1" }, { n: 2, claimId: "cl2", sourceId: "src2" }],
    claims: {
      cl1: { text: "Students report lost items from their phones.", trustLabel: "SUPPORTED", labelReason: {}, quote: "q", sourceId: "src1", chunkId: "c1", quoteStart: 0, quoteEnd: 1 },
      cl2: { text: "About 40 percent of items are returned.", trustLabel: "WEAK", labelReason: {}, quote: "q", sourceId: "src2", chunkId: "c2", quoteStart: 0, quoteEnd: 1 },
    },
    versions: [{ version: 1, createdAt: "2026-11-12T06:00:00Z", byYou: false }],
    sources: {
      src1: { title: "Lost and found apps compared", site: "campus.example", type: "OTHER", credibilityTier: "LOW", credibilityReason: "Personal blog / unknown author", url: "https://campus.example/a" },
      src2: { title: "Item recovery study", site: "study.example", type: "PREPRINT", credibilityTier: "MEDIUM", credibilityReason: "Preprint (not peer-reviewed), 2024", url: "https://study.example/b" },
    },
  };
}

const CHUNK_2 =
  "We surveyed 300 students on four campuses. Some apps return about 40 percent of items to their owners within a week. " +
  "Paper notice boards returned far fewer.";
const QUOTE_2 = "Some apps return about 40 percent of items to their owners within a week.";

/** F-FR-1: the passage behind each fake claim, with the server's quote span. */
const PASSAGES: Record<string, unknown> = {
  cl2: {
    claimId: "cl2",
    claimText: "About 40 percent of items are returned.",
    trustLabel: "WEAK",
    quote: QUOTE_2,
    chunkText: CHUNK_2,
    highlight: { start: CHUNK_2.indexOf(QUOTE_2), end: CHUNK_2.indexOf(QUOTE_2) + QUOTE_2.length },
    sourceId: "src2",
    source: { title: "Item recovery study", site: "study.example", type: "PREPRINT", credibilityTier: "MEDIUM", credibilityReason: "Preprint (not peer-reviewed), 2024", url: "https://study.example/b", publishedAt: "2024-03-01" },
    evidence: [
      { sourceId: "src2", title: "Item recovery study", site: "study.example", credibilityTier: "MEDIUM", verdict: "PARTIAL", own: true },
      { sourceId: "src1", title: "Lost and found apps compared", site: "campus.example", credibilityTier: "LOW", verdict: "INSUFFICIENT", own: false },
    ],
  },
};

function runView(run: FakeRun) {
  const sections =
    run.config.sections?.length ?? TEMPLATES.find((t) => t.code === run.config.templateCode)?.sections.length ?? 5;
  const size = sizeOf(run.config.depth, run.config)!;
  const planning = run.status === "PLANNING";
  const e = !planning && run.subQuestions.length > 0 ? estimate(size, run.subQuestions.length, sections) : null;
  return {
    id: run.id,
    status: run.status,
    progress: run.status === "RUNNING" ? 10 : 0,
    topic: run.topic,
    config: { objective: "PROJECT_REPORT", outputStyle: "PROJECT_REPORT", timeRange: "ANY", language: "en", ...run.config },
    subQuestions: planning ? [] : run.subQuestions,
    estimate: e && {
      requests: e.requests,
      totalRequests: e.total,
      minutes: e.minutes,
      sections,
      sourcesPerQuestion: size.sourcesPerQuestion,
      minutesPerQuestion: size.minutesPerQuestion,
      perConnection: [],
      routes: [],
      unroutedSlots: [],
    },
    steps: [
      {
        id: `${run.id}-plan`,
        seq: 1,
        stage: 0,
        kind: "PLAN",
        status: planning ? "RUNNING" : run.planError ? "FAILED" : "DONE",
        attempts: run.planError ? 3 : 1,
        lastError: run.planError,
      },
    ],
    version: run.version,
    createdAt: "2026-11-11T06:00:00Z",
    updatedAt: "2026-11-11T06:00:00Z",
    reportId: run.status === "COMPLETED" ? (run.reportId ?? null) : null,
  };
}
