import { describe, expect, test, vi } from "vitest";

import { csrfMiddleware, readCookie, unauthorizedMiddleware } from "@/lib/api/client";

type Ctx = Parameters<NonNullable<ReturnType<typeof csrfMiddleware>["onRequest"]>>[0];

function requestCtx(method: string): { request: Request } {
  return { request: new Request("http://localhost:8080/api/v1/connections", { method }) };
}

async function runCsrf(method: string, cookie: string | undefined) {
  const mw = csrfMiddleware(() => cookie);
  const { request } = requestCtx(method);
  const out = (await mw.onRequest!({ request } as Ctx)) as Request;
  return out.headers.get("X-XSRF-TOKEN");
}

describe("T-08-01 API client CSRF middleware", () => {
  test.each(["POST", "PUT", "PATCH", "DELETE"])("%s carries X-XSRF-TOKEN from the cookie", async (method) => {
    expect(await runCsrf(method, "abc-123")).toBe("abc-123");
  });

  test.each(["GET", "HEAD", "OPTIONS"])("%s never carries X-XSRF-TOKEN", async (method) => {
    expect(await runCsrf(method, "abc-123")).toBeNull();
  });

  test("URL-encoded cookie value is decoded; no cookie → no header", async () => {
    expect(await runCsrf("POST", "a%2Bb%3D")).toBe("a+b=");
    expect(await runCsrf("POST", undefined)).toBeNull();
  });

  test("readCookie finds the right cookie and keeps '=' inside values", () => {
    const jar = "ANV_SESSION=s1; XSRF-TOKEN=tok=en; other=x";
    expect(readCookie("XSRF-TOKEN", jar)).toBe("tok=en");
    expect(readCookie("missing", jar)).toBeUndefined();
    expect(readCookie("SESSION", jar)).toBeUndefined();
  });
});

describe("T-08-02 API client 401 middleware", () => {
  type RespCtx = Parameters<NonNullable<ReturnType<typeof unauthorizedMiddleware>["onResponse"]>>[0];

  async function respond(status: number, path: string) {
    const navigate = vi.fn();
    const mw = unauthorizedMiddleware(navigate, () => path);
    await mw.onResponse!({ response: new Response(null, { status }) } as RespCtx);
    return navigate;
  }

  test("401 → redirect to /login?expired=1 with the page to come back to", async () => {
    const navigate = await respond(401, "/settings/ai");
    expect(navigate).toHaveBeenCalledWith("/login?expired=1&next=%2Fsettings%2Fai");
  });

  test("no redirect loop on the login page, and no redirect for 403/404/500", async () => {
    expect(await respond(401, "/login")).not.toHaveBeenCalled();
    for (const status of [200, 403, 404, 500]) {
      expect(await respond(status, "/dashboard")).not.toHaveBeenCalled();
    }
  });
});
