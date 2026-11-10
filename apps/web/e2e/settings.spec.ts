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
