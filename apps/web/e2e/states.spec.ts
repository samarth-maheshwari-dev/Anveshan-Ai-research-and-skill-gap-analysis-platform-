import { expect, test, type Page } from "@playwright/test";

import { API, APP, installFakeApi, verifiedConnection } from "./fake-api";

/** Error states (APPFLOW §2.3): a failed load says so and offers a retry — never "empty" or an endless skeleton. */

async function failGet(page: Page, path: string) {
  // registered after installFakeApi, so it wins for this one path
  await page.route(`${API}/api/v1${path}`, (route) =>
    route.request().method() === "GET"
      ? route.fulfill({
          status: 500,
          contentType: "application/problem+json",
          headers: { "access-control-allow-origin": APP, "access-control-allow-credentials": "true" },
          body: JSON.stringify({ status: 500, code: "UPSTREAM_UNAVAILABLE", title: "Error", detail: "x" }),
        })
      : route.fallback(),
  );
}

test("T-08-29 connections fail to load → dashboard shows an error with retry, not 'No AI connected yet'", async ({
  page,
  context,
}) => {
  // a user who has a working key (so /me doesn't send them to the wizard), but the list itself fails
  await installFakeApi(page, context, { signedIn: true, connections: [verifiedConnection()] });
  await failGet(page, "/connections");
  await page.goto("/dashboard");
  await expect(page.getByRole("alert").filter({ hasText: "Couldn't load your AI connections." })).toBeVisible();
  await expect(page.getByText("No AI connected yet.")).toHaveCount(0);
});

test("T-08-29 slots fail to load → Task routing shows an error with retry, not an endless skeleton", async ({
  page,
  context,
}) => {
  await installFakeApi(page, context, { signedIn: true });
  await failGet(page, "/slots");
  await page.goto("/settings/ai");
  const alert = page.getByRole("alert").filter({ hasText: "Couldn't load your task routing." });
  await expect(alert).toBeVisible();
  await expect(alert.getByRole("button", { name: "Try again" })).toBeVisible();
});
