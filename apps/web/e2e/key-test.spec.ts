import { expect, test, type Page } from "@playwright/test";

import { APP, GOOD_KEY, installFakeApi, verifiedConnection } from "./fake-api";

/** Samarth's bug report (P08 rework): paste key → "model isn't available" → … → re-test always failing. */

async function connectGemini(page: Page) {
  await page.goto("/onboarding/connect-ai");
  await page.getByRole("button", { name: /Google Gemini/ }).click();
  await page.getByRole("button", { name: "I have my key" }).click();
  await page.getByLabel("API key").fill(GOOD_KEY);
  await page.getByRole("button", { name: "Connect" }).click();
}

test("T-08-20 paste key while the provider's first-listed model is retired → still Connected, no red bar", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    listedModels: ["gemini-3-flash", "gemini-2.5-flash"],
    retiredModels: ["gemini-3-flash"],
  });

  await connectGemini(page);

  await expect(page.getByRole("heading", { name: "Connected. Google Gemini is ready." })).toBeVisible();
  await expect(page.getByText("gemini-2.5-flash")).toBeVisible(); // the model the backend proved
  await expect(page.getByRole("alert").filter({ hasText: /model|key/i })).toHaveCount(0);
  expect(api.testModelsAsked).toEqual([undefined]); // one test, model chosen by the backend
  expect(api.connections[0]?.status).toBe("VERIFIED");
});

test("T-08-21 Settings → Test right after the wizard stays Verified (twice) and no failing banner appears", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    listedModels: ["gemini-3-flash", "gemini-2.5-flash"],
    retiredModels: ["gemini-3-flash"],
  });
  await connectGemini(page);
  await expect(page.getByRole("heading", { name: /is ready/ })).toBeVisible();

  await page.goto("/settings/ai");
  const row = page.getByTestId("connection-row");
  for (let i = 0; i < 2; i++) {
    await row.getByRole("button", { name: "Test" }).click();
    await expect(page.getByText("My Google Gemini works.").last()).toBeVisible();
    await expect(page.getByText("Checked with gemini-2.5-flash").last()).toBeVisible();
    await expect(row.getByText("Verified")).toBeVisible();
  }
  expect(api.testModelsAsked).toEqual([undefined, undefined, undefined]);
  expect(api.connections[0]?.status).toBe("VERIFIED");

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "Welcome, Riya" })).toBeVisible();
  await expect(page.getByTestId("global-banner")).toHaveCount(0);
});

test("T-08-22 no model works → 'Choose one from the model list' + a chat-only picker → picking one connects", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    listedModels: ["gemini-3-flash", "gemini-2.5-flash-lite"],
    retiredModels: ["gemini-3-flash", "gemini-2.5-flash-lite"],
  });
  await connectGemini(page);

  await expect(page.getByRole("alert").filter({ hasText: "model" })).toHaveText(
    "Your key works, but we couldn't find a model it can use. Choose one from the model list.",
  );
  const picker = page.getByLabel("Model");
  await expect(picker).toBeVisible();
  await expect(picker.locator("option", { hasText: "text-embedding-004" })).toHaveCount(0); // chat models only
  expect(api.connections[0]?.status).toBe("UNVERIFIED"); // a missing model never marks the key failing

  api.retiredModels = ["gemini-3-flash"]; // the user picks one that works for this key
  await picker.selectOption("gemini-2.5-flash-lite");
  await page.getByRole("button", { name: "Test again" }).click();

  await expect(page.getByRole("heading", { name: "Connected. Google Gemini is ready." })).toBeVisible();
  await expect(page.getByText("gemini-2.5-flash-lite")).toBeVisible();
  expect(api.testModelsAsked).toEqual([undefined, "gemini-2.5-flash-lite"]);
});

test("T-08-28 custom endpoint lists no model (no preset hint either) → typed 'Model name' field, told to type → connects", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, { signedIn: true, listedModels: [] });
  await page.goto("/onboarding/connect-ai");
  await page.getByRole("button", { name: "I already have a key from another provider" }).click();
  await page.getByRole("button", { name: /Custom \(OpenAI-compatible\)/ }).click();
  await page.getByRole("button", { name: "I have my key" }).click();
  await page.getByLabel("Base URL").fill("https://llm.example.org/v1/");
  await page.getByLabel("API key").fill(GOOD_KEY);
  await page.getByRole("button", { name: "Connect" }).click();

  const typed = page.getByLabel("Model name");
  await expect(typed).toBeVisible();
  // the message matches what's on screen: no list here, so it never says "choose from the list"
  await expect(page.getByRole("alert").filter({ hasText: "model" })).toHaveText(
    "Your key works, but we couldn't find a model it can use. Type a model name below.",
  );
  await expect(
    page.getByText("Custom (OpenAI-compatible) didn't give us a list of chat models.", { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Test again" })).toBeDisabled();

  // T-08-33 a wrong typed model: still told to type (never "the list"), and the list isn't fetched again
  api.retiredModels = ["wrong-model"];
  await typed.fill("wrong-model");
  await page.getByRole("button", { name: "Test again" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "model" })).toHaveText(
    "Your key works, but this model isn't available. Type a model name below.",
  );
  expect(api.calls.filter((c) => c.endsWith("/models"))).toHaveLength(1);

  // T-08-35 the provider is down for a moment: the typed field stays, with the model the user typed
  await page.route("**/api/v1/connections/*/test", (route) =>
    route.fulfill({
      status: 422,
      headers: {
        "access-control-allow-origin": APP,
        "access-control-allow-credentials": "true",
        "content-type": "application/problem+json",
      },
      body: JSON.stringify({ status: 422, code: "CONNECTION_TEST_FAILED", detail: "x", reason: "PROVIDER_UNAVAILABLE" }),
    }),
    { times: 1 },
  );
  await page.getByRole("button", { name: "Test again" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Couldn't reach" })).toHaveText(
    "Couldn't reach Custom (OpenAI-compatible) right now. Try again in a minute.",
  );
  await expect(typed).toBeVisible();
  await expect(typed).toHaveValue("wrong-model");

  await typed.fill("my-org/chat-model-1");
  await page.getByRole("button", { name: "Try again" }).click();

  await expect(page.getByRole("heading", { name: /Connected\. Custom \(OpenAI-compatible\) is ready\./ })).toBeVisible();
  expect(api.testModelsAsked).toEqual([undefined, "wrong-model", "my-org/chat-model-1"]);
});

test("T-08-36 pasting again while the first test runs → ignored: one connection, one create request", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, { signedIn: true });
  // a slow create, so the second paste lands while the first is still in flight
  await page.route("**/api/v1/connections", async (route) => {
    if (route.request().method() === "POST") await new Promise((r) => setTimeout(r, 1000));
    await route.fallback();
  });
  await page.goto("/onboarding/connect-ai");
  await page.getByRole("button", { name: /Google Gemini/ }).click();
  await page.getByRole("button", { name: "I have my key" }).click();

  const paste = () =>
    page.getByLabel("API key").evaluate((el, key) => {
      const data = new DataTransfer();
      data.setData("text", key);
      el.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
    }, GOOD_KEY);
  await paste();
  await paste(); // same tick-ish: before React re-rendered "busy"
  await page.waitForTimeout(300);
  await paste(); // later, while the create is still waiting

  await expect(page.getByRole("heading", { name: "Connected. Google Gemini is ready." })).toBeVisible();
  expect(api.calls.filter((c) => c === "POST /connections")).toHaveLength(1);
  expect(api.connections).toHaveLength(1);
  expect(api.testModelsAsked).toEqual([undefined]);
});

test("T-08-31 Gemini lists no model → the backend still tries its known-good model, so no dead end at all", async ({
  page,
  context,
}) => {
  const api = await installFakeApi(page, context, { signedIn: true, listedModels: [] });
  await connectGemini(page);
  await expect(page.getByRole("heading", { name: "Connected. Google Gemini is ready." })).toBeVisible();
  await expect(page.getByText("gemini-2.5-flash")).toBeVisible();
  expect(api.testModelsAsked).toEqual([undefined]);
});

test("T-08-32 Settings → Test when the routed model is gone → warning toast says research still uses it", async ({
  page,
  context,
}) => {
  const gemini = verifiedConnection({ id: "c-gem", provider: "GEMINI", label: "My Gemini", key: GOOD_KEY });
  const slots = Object.fromEntries(
    ["PLAN", "EXTRACT", "VERIFY", "WRITE", "ASK", "INTERVIEW"].map((s) => [s, [{ connectionId: "c-gem", model: "gemini-2.5-flash" }]]),
  );
  await installFakeApi(page, context, {
    signedIn: true,
    connections: [gemini],
    slots,
    listedModels: ["gemini-2.5-flash", "gemini-3-flash"],
    retiredModels: ["gemini-2.5-flash"],
  });
  await page.goto("/settings/ai");
  await page.getByTestId("connection-row").getByRole("button", { name: "Test" }).click();

  const toast = page.locator('[data-sonner-toast][data-type="warning"]');
  // one assertion for type + title + description, so the toast can't time out between two checks
  await expect(toast).toHaveText(
    /My Gemini: gemini-2\.5-flash didn't answer, gemini-3-flash did\. Research still uses gemini-2\.5-flash — change it in Task routing\s*Checked with gemini-3-flash/,
  );
});
