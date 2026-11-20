import { expect, test } from "@playwright/test";

import { installFakeApi, verifiedConnection } from "./fake-api";

test("Settings → AI: list shows only ••••last4, test works, delete asks first", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection({ id: "g1", label: "My Gemini", provider: "GEMINI", key: "AIzaSecretSecret9XyZ" })],
  });
  await page.goto("/settings/ai");

  const row = page.getByTestId("connection-row");
  await expect(row).toContainText("My Gemini");
  await expect(row).toContainText("••••9XyZ");
  await expect(page.locator("body")).not.toContainText("AIzaSecretSecret");

  await row.getByRole("button", { name: "Test" }).click();
  await expect(page.getByText("My Gemini works.")).toBeVisible();

  await row.getByRole("button", { name: "Delete My Gemini" }).click();
  await expect(row.getByText("Delete?")).toBeVisible();
  await row.getByRole("button", { name: "Delete", exact: true }).click();
  await expect(page.getByText("No connections yet.")).toBeVisible();
  expect(api.deleted).toEqual(["g1"]);
  expect(api.writesWithoutCsrf).toEqual([]);
});

test("Settings → AI: task routing saves primary + fallback per slot", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [
      verifiedConnection({ id: "g1", label: "My Gemini", provider: "GEMINI" }),
      verifiedConnection({ id: "q1", label: "My Groq" }),
    ],
    slots: { PLAN: [{ connectionId: "g1", model: "gemini-2.5-flash" }] },
  });
  await page.goto("/settings/ai");

  const plan = page.getByRole("group", { name: "Plan" });
  await expect(plan.getByLabel("Model").first()).toHaveValue("gemini-2.5-flash");
  await plan.getByRole("button", { name: "Add fallback" }).click();
  await plan.getByLabel("Fallback 1").selectOption({ label: "My Groq" });
  await plan.getByLabel("Model").nth(1).fill("llama-3.3-70b-versatile");
  await page.getByRole("button", { name: "Save routing" }).click();

  await expect(page.getByText("Task routing saved.")).toBeVisible();
  expect(api.slots["PLAN"]).toEqual([
    { connectionId: "g1", model: "gemini-2.5-flash" },
    { connectionId: "q1", model: "llama-3.3-70b-versatile" },
  ]);
});

test("T-15-26 Settings → AI: optional web-search key — save + test, shown as ••••last4, never in the AI list or routing", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection({ id: "g1", label: "My Gemini", provider: "GEMINI" })],
  });
  await page.goto("/settings/ai");

  const card = page.getByTestId("search-key-section");
  await expect(card).toContainText("Web search key (optional)");
  await card.getByRole("button", { name: "Save and test" }).click();
  await expect(card.getByRole("alert")).toHaveText("Paste your Tavily key first.");

  await card.getByLabel("Tavily API key").fill("tvly-secretsecret77Ab");
  await card.getByRole("button", { name: "Save and test" }).click();
  await expect(page.getByText("Tavily web search works.")).toBeVisible();
  await expect(card.getByTestId("connection-row")).toContainText("••••77Ab");
  await expect(page.locator("body")).not.toContainText("tvly-secretsecret");

  const created = api.connections.find((c) => c.provider === "TAVILY");
  expect(created?.kind).toBe("SEARCH");
  // the AI connections list shows only the LLM key
  const aiRows = page.getByTestId("connection-row").filter({ hasNotText: "Tavily" });
  await expect(aiRows).toHaveCount(1);
  expect(api.writesWithoutCsrf).toEqual([]);
});
