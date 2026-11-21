import { expect, test } from "@playwright/test";

import { API, APP, installFakeApi } from "./fake-api";

/** Error states (APPFLOW §2.3): a failed load says so and offers a retry — never "empty" or an endless skeleton. */

test("P16: AI status fails to load → the dashboard card says so instead of a fake state", async ({ page, context }) => {
  await installFakeApi(page, context, { signedIn: true });
  await page.route(`${API}/api/ai/status`, (route) =>
    route.fulfill({
      status: 500,
      contentType: "application/problem+json",
      headers: { "access-control-allow-origin": APP, "access-control-allow-credentials": "true" },
      body: JSON.stringify({ status: 500, code: "UPSTREAM_UNAVAILABLE", title: "Error", detail: "x" }),
    }),
  );
  await page.goto("/dashboard");
  await expect(page.getByText("Couldn't check the AI right now.")).toBeVisible();
});

test("P16: dashboard AI status card shows the state as a word and today's usage", async ({ page, context }) => {
  await installFakeApi(page, context, { signedIn: true, aiStatus: { state: "BUSY", runsUsedToday: 2, runsLimit: 5 } });
  await page.goto("/dashboard");
  await expect(page.getByText("Busy", { exact: true })).toBeVisible();
  await expect(page.getByText("2 of 5 research runs used today")).toBeVisible();
});
