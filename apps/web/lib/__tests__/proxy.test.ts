import { NextRequest } from "next/server";
import { describe, expect, test } from "vitest";

import { safeNext } from "@/app/(public)/login/login-panel";
import { isProtected, proxy } from "@/proxy";

function request(path: string, withSession: boolean) {
  const req = new NextRequest(`http://localhost:3000${path}`);
  if (withSession) req.cookies.set("ANV_SESSION", "abc");
  return req;
}

describe("route protection proxy (TECHSPEC §9.3)", () => {
  test("app pages without a session cookie → /login?next=<page>", () => {
    const res = proxy(request("/settings/ai", false));
    expect(res.status).toBe(307);
    expect(res.headers.get("location")).toBe("http://localhost:3000/login?next=%2Fsettings%2Fai");
  });

  test("signed-in visitor on the landing page → dashboard", () => {
    expect(proxy(request("/", true)).headers.get("location")).toBe("http://localhost:3000/dashboard");
  });

  test("public pages and signed-in app pages pass through", () => {
    for (const [path, session] of [["/", false], ["/login", false], ["/dashboard", true]] as const) {
      expect(proxy(request(path, session)).headers.get("location")).toBeNull();
    }
  });

  test("prefix matching is exact: /dashboardx and /wiki are not protected", () => {
    expect(isProtected("/dashboard")).toBe(true);
    expect(isProtected("/w/123")).toBe(true);
    expect(isProtected("/dashboardx")).toBe(false);
    expect(isProtected("/wiki")).toBe(false);
  });
});

describe("login ?next is passed on only for same-app paths", () => {
  test.each([
    ["/reports/1", "/reports/1"],
    ["https://evil.example", undefined],
    ["//evil.example", undefined],
    ["/\\evil.example", undefined],
    [null, undefined],
  ])("%s → %s", (input, expected) => {
    expect(safeNext(input)).toBe(expected);
  });
});
