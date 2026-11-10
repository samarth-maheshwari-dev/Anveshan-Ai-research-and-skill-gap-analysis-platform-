import { expect, test } from "@playwright/test";

import { BAD_KEY, GOOD_KEY, installFakeApi, verifiedConnection } from "./fake-api";

test("T-08-03 Riya: landing → login (fake OIDC) → wizard → paste key → Connected → dashboard, in < 2 min", async ({
  page,
  context,
}) => {
  const started = Date.now();
  const api = await installFakeApi(page, context);

  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Research you can defend. Skills you can prove.");
  await page.getByRole("link", { name: "Start free" }).first().click();

  await expect(page).toHaveURL(/\/login$/);
  await page.getByRole("link", { name: "Continue with Google" }).click();

  // First sign-in without a key: the dashboard sends Riya to the wizard once.
  await expect(page).toHaveURL(/\/onboarding\/connect-ai$/);
  await page.getByRole("button", { name: /Google Gemini/ }).click();
  await expect(page.getByRole("link", { name: /Open Google AI Studio/ })).toHaveAttribute(
    "href",
    "https://aistudio.google.com/app/apikey",
  );
  await page.getByRole("button", { name: "I have my key" }).click();
  await page.getByLabel("API key").fill(GOOD_KEY);
  await page.getByRole("button", { name: "Connect" }).click();

  await expect(page.getByRole("heading", { name: "Connected. Google Gemini is ready." })).toBeVisible();
  await expect(page.getByText("Verified", { exact: true })).toBeVisible();
  await expect(page.getByText("gemini-2.5-flash")).toBeVisible(); // default slots filled with the preferred model
  await page.getByRole("button", { name: "Start researching" }).click();

  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("heading", { name: "Welcome, Riya" })).toBeVisible();
  await expect(page.getByTestId("global-banner")).toHaveCount(0);
  const aiCard = page.getByRole("listitem").filter({ hasText: "My Google Gemini" });
  await expect(aiCard.getByText("Verified")).toBeVisible();

  expect(api.calls).toEqual(
    expect.arrayContaining(["oauth", "POST /connections", "GET /connections/c1/models", "POST /connections/c1/test"]),
  );
  expect(api.writesWithoutCsrf).toEqual([]); // every write carried X-XSRF-TOKEN
  expect(api.connections[0]?.status).toBe("VERIFIED");
  expect(Date.now() - started).toBeLessThan(120_000);
});

test("T-08-04 invalid key → \"This key didn't work…\", input cleared, broken connection removed", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, { signedIn: true });
  await page.goto("/onboarding/connect-ai");
  await page.getByRole("button", { name: /Google Gemini/ }).click();
  await page.getByRole("button", { name: "I have my key" }).click();

  const input = page.getByLabel("API key");
  await input.fill(BAD_KEY);
  await page.getByRole("button", { name: "Connect" }).click();

  // (Next.js has its own empty route-announcer alert; pick ours by text.)
  await expect(page.getByRole("alert").filter({ hasText: "key" })).toHaveText(
    "This key didn't work. Check that you copied the whole key.",
  );
  await expect(input).toHaveValue("");
  await expect(input).toBeFocused();
  await expect.poll(() => api.deleted).toEqual(["c1"]);
  expect(api.connections).toHaveLength(0);

  // Recovery: paste a good key and continue.
  await input.fill(GOOD_KEY);
  await page.getByRole("button", { name: "Connect" }).click();
  await expect(page.getByRole("heading", { name: /is ready/ })).toBeVisible();
});

test("T-08-05 signed-in user with no connection opens the dashboard → banner #2 'Connect an AI provider…'", async ({
  page,
  context,
}) => {
  await installFakeApi(page, context, { signedIn: true });
  await page.addInitScript(() => window.sessionStorage.setItem("anv.onboarding.offered", "1"));
  await page.goto("/dashboard");

  const banner = page.getByTestId("global-banner");
  await expect(banner).toContainText("Connect an AI provider to start researching.");
  await banner.getByRole("link", { name: "Connect AI" }).click();
  await expect(page).toHaveURL(/\/onboarding\/connect-ai$/);
  await expect(page.getByTestId("global-banner")).toHaveCount(0); // never on the wizard itself
});

test("banner #3: a failing key is named and links to Settings → AI", async ({ page, context }) => {
  await installFakeApi(page, context, {
    signedIn: true,
    connections: [
      verifiedConnection({ id: "ok", label: "My Gemini" }),
      verifiedConnection({ id: "bad", label: "My Groq", status: "FAILING" }),
    ],
  });
  await page.goto("/dashboard");
  const banner = page.getByTestId("global-banner");
  await expect(banner).toContainText("Your My Groq key stopped working.");
  await banner.getByRole("link", { name: "Fix" }).click();
  await expect(page).toHaveURL(/\/settings\/ai$/);
});

test("signed out: app pages redirect to login; an expired session shows 'Your session expired'", async ({
  page,
  context,
}) => {
  await installFakeApi(page, context);
  await page.goto("/settings/ai");
  await expect(page).toHaveURL(/\/login\?next=%2Fsettings%2Fai$/);
  await expect(page.getByRole("link", { name: "Continue with Google" })).toHaveAttribute(
    "href",
    "http://localhost:8080/oauth2/authorization/google?next=%2Fsettings%2Fai",
  );

  // Cookie still there but the server session is gone → /me 401 → login with a message.
  await context.addCookies([{ name: "ANV_SESSION", value: "stale", domain: "localhost", path: "/" }]);
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/login\?expired=1/);
  await expect(page.getByText("Your session expired. Please sign in again.")).toBeVisible();
});

test("sign out from the user menu ends the session", async ({ page, context }) => {
  const api = await installFakeApi(page, context, { signedIn: true, connections: [verifiedConnection()] });
  await page.goto("/dashboard");
  await page.getByRole("button", { name: "Account menu" }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(api.calls).toContain("POST /auth/logout");
  expect(api.writesWithoutCsrf).toEqual([]);
});
