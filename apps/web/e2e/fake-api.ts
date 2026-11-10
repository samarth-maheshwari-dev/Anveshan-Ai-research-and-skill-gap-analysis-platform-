import type { BrowserContext, Page, Route } from "@playwright/test";

/**
 * An in-memory stand-in for the Anveshan API (http://localhost:8080), installed per test with
 * page.route. It behaves like the real backend where the UI depends on it (ByokIT proves the real
 * one): session cookie, CSRF header on writes, 401 without a session, and the connection test —
 * the backend picks the model (routed → preset hint → ranked list, max 5 (anveshan.models.test-candidates), skipping retired ones), a
 * skipped routed model comes back as a `note`, a missing model never marks the key FAILING, errors
 * carry a machine-readable `reason`, first success fills slots.
 */
export const API = "http://localhost:8080";
export const APP = "http://localhost:3100";
export const GOOD_KEY = "AIzaGoodKeyForTests1234567890abcdWXYZ";
export const BAD_KEY = "AIzaBadKeyForTests12345678901234567890";

type Conn = {
  id: string;
  kind: "LLM" | "SEARCH";
  provider: string;
  label: string;
  baseUrl: string;
  keyLast4: string;
  status: "UNVERIFIED" | "VERIFIED" | "FAILING";
  statusDetail?: string;
  key: string;
};

export type FakeState = {
  signedIn: boolean;
  connections: Conn[];
  slots: Record<string, { connectionId: string; model: string }[]>;
  /** Chat models the provider lists, best first (what the backend's ranking would produce). */
  listedModels: string[];
  /** Listed but answering "model not found" for this key (e.g. retired for new projects). */
  retiredModels: string[];
  writesWithoutCsrf: string[];
  deleted: string[];
  calls: string[];
  /** The `model` each POST …/test request carried (undefined = "backend, you choose"). */
  testModelsAsked: (string | undefined)[];
};

const SLOTS = ["PLAN", "EXTRACT", "VERIFY", "WRITE", "ASK", "INTERVIEW"];

/** The presets' known-good `test-model` (application.yml); OpenRouter, Anthropic and Custom have none. */
const HINTS: Record<string, string> = {
  GEMINI: "gemini-2.5-flash",
  GROQ: "llama-3.3-70b-versatile",
  OPENAI: "gpt-4.1-mini",
};

export async function installFakeApi(page: Page, context: BrowserContext, init: Partial<FakeState> = {}) {
  const state: FakeState = {
    signedIn: false,
    connections: [],
    slots: {},
    listedModels: ["gemini-2.5-flash", "gemini-2.0-flash"],
    retiredModels: [],
    writesWithoutCsrf: [],
    deleted: [],
    calls: [],
    testModelsAsked: [],
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

  const cors = { "access-control-allow-origin": APP, "access-control-allow-credentials": "true" };
  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({ status, headers: { ...cors, "content-type": "application/json" }, body: JSON.stringify(body) });
  const problem = (route: Route, status: number, code: string, detail: string, reason?: string) =>
    route.fulfill({
      status,
      headers: { ...cors, "content-type": "application/problem+json" },
      body: JSON.stringify({ status, code, title: "Error", detail, ...(reason ? { reason } : {}) }),
    });
  const keyInvalid = (route: Route) => problem(route, 422, "CONNECTION_TEST_FAILED", "Key invalid", "KEY_INVALID");
  /** What the API returns: everything except the key. */
  const view = (c: Conn) => Object.fromEntries(Object.entries(c).filter(([k]) => k !== "key"));
  const routedTo = (c: Conn) =>
    Object.values(state.slots)
      .flat()
      .filter((r) => r.connectionId === c.id)
      .map((r) => r.model);
  /**
   * Like ConnectionService.candidates: routed models → the preset hint (only if the provider lists it,
   * or lists nothing) → the ranked list; at most 5.
   */
  const candidatesFor = (c: Conn, routed: string[]) => {
    const hint = HINTS[c.provider];
    const useHint = hint && (state.listedModels.length === 0 || state.listedModels.includes(hint));
    return [...new Set([...routed, ...(useHint ? [hint] : []), ...state.listedModels])].slice(0, 5);
  };

  // OAuth start: the real API redirects to Google; here "Google" approves at once and comes back.
  await page.route(`${API}/oauth2/authorization/**`, async (route) => {
    state.calls.push("oauth");
    await signIn();
    await route.fulfill({ status: 302, headers: { location: `${APP}/dashboard` } });
  });

  await page.route(`${API}/api/v1/**`, async (route) => {
    const req = route.request();
    const method = req.method();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    if (method === "OPTIONS") {
      return route.fulfill({
        status: 204,
        headers: { ...cors, "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE", "access-control-allow-headers": "Content-Type,X-XSRF-TOKEN" },
      });
    }
    state.calls.push(`${method} ${path}`);
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
        onboarding: { hasVerifiedConnection: state.connections.some((c) => c.status === "VERIFIED"), hasRun: false },
      });
    }
    if (path === "/auth/logout" && method === "POST") {
      state.signedIn = false;
      await context.clearCookies();
      return route.fulfill({ status: 204, headers: cors });
    }
    if (path === "/connections" && method === "GET") {
      return json(route, 200, { items: state.connections.map(view) });
    }
    if (path === "/connections" && method === "POST") {
      const body = req.postDataJSON() as { provider: string; label: string; apiKey: string; kind: "LLM" };
      const c: Conn = {
        id: `c${++seq}`,
        kind: body.kind,
        provider: body.provider,
        label: body.label,
        baseUrl: "https://example.test/",
        keyLast4: body.apiKey.slice(-4),
        status: "UNVERIFIED",
        key: body.apiKey,
      };
      state.connections.push(c);
      return json(route, 201, view(c));
    }
    const byId = path.match(/^\/connections\/([^/]+)(\/test|\/models)?$/);
    if (byId) {
      const c = state.connections.find((x) => x.id === byId[1]);
      if (!c) return problem(route, 404, "NOT_FOUND", "This item doesn't exist.");
      if (method === "DELETE") {
        state.connections = state.connections.filter((x) => x !== c);
        state.deleted.push(c.id);
        return route.fulfill({ status: 204, headers: cors });
      }
      if (byId[2] === "/models") {
        if (c.key === BAD_KEY) return keyInvalid(route);
        // Chat models first (ranked), non-chat ones last and flagged — like the real endpoint.
        return json(route, 200, {
          items: [
            ...state.listedModels.map((id) => ({ id, displayName: id, free: true, chat: true })),
            { id: "text-embedding-004", displayName: "Embedding", free: true, chat: false },
          ],
        });
      }
      if (byId[2] === "/test") {
        const asked = (req.postDataJSON() as { model?: string } | null)?.model;
        state.testModelsAsked.push(asked);
        if (c.key === BAD_KEY) {
          c.status = "FAILING";
          c.statusDetail = "Key invalid";
          return keyInvalid(route);
        }
        const routed = asked ? [] : routedTo(c);
        const working = (asked ? [asked] : candidatesFor(c, routed)).find((m) => !state.retiredModels.includes(m));
        if (!working) {
          // a missing model is not a broken key: status stays as it was
          return asked
            ? problem(route, 422, "CONNECTION_TEST_FAILED", "Model not found", "MODEL_NOT_FOUND")
            : problem(route, 422, "CONNECTION_TEST_FAILED", "No usable chat model found for this key", "NO_CHAT_MODEL");
        }
        // like ConnectionService.skippedNote: a routed model that's gone is reported, research still uses it
        const skipped = routed.find((m) => state.retiredModels.includes(m));
        const note = skipped
          ? `${skipped} didn't answer, ${working} did. Research still uses ${skipped} — change it in Task routing`
          : undefined;
        c.status = "VERIFIED";
        c.statusDetail = note;
        const fill = Object.keys(state.slots).length === 0;
        if (fill) {
          for (const s of SLOTS) state.slots[s] = [{ connectionId: c.id, model: working }];
        }
        return json(route, 200, { status: "VERIFIED", latencyMs: 420, sampleModel: working, note, defaultSlotsSet: fill });
      }
    }
    if (path === "/slots" && method === "GET") return json(route, 200, state.slots);
    if (path === "/slots" && method === "PUT") {
      state.slots = req.postDataJSON() as FakeState["slots"];
      return json(route, 200, state.slots);
    }
    return problem(route, 404, "NOT_FOUND", `No fake for ${method} ${path}`);
  });

  return state;
}

export function verifiedConnection(overrides: Partial<Conn> = {}): Conn {
  return {
    id: "c-existing",
    kind: "LLM",
    provider: "GROQ",
    label: "My Groq",
    baseUrl: "https://api.groq.com/openai/v1/",
    keyLast4: "9XyZ",
    status: "VERIFIED",
    key: "gsk_existing",
    ...overrides,
  };
}
