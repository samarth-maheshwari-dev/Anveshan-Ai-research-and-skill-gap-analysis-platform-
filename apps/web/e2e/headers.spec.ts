import { expect, test } from "@playwright/test";

test("security headers on every page (TECHSPEC §9.8)", async ({ request }) => {
  for (const path of ["/", "/login"]) {
    const res = await request.get(path);
    const h = res.headers();
    expect(h["x-content-type-options"], path).toBe("nosniff");
    expect(h["x-frame-options"], path).toBe("DENY");
    expect(h["referrer-policy"], path).toBe("strict-origin-when-cross-origin");
    expect(h["content-security-policy-report-only"], path).toContain("frame-ancestors 'none'");
    expect(h["x-powered-by"], path).toBeUndefined();
  }
});
