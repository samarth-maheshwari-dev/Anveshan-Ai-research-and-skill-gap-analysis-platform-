import { expect, test, type Page } from "@playwright/test";

import { fakeRun, installFakeApi } from "./fake-api";

const QUOTE = "Some apps return about 40 percent of items to their owners within a week.";

async function openReport(page: Page, context: Parameters<typeof installFakeApi>[1]) {
  const state = await installFakeApi(page, context, {
    signedIn: true,
    runs: [fakeRun({ id: "r-done", status: "COMPLETED", reportId: "rep-1", topic: "Campus lost-and-found apps" })],
  });
  await page.goto("/research/r-done/report");
  await expect(page.getByRole("heading", { level: 1, name: "Campus lost-and-found apps" })).toBeVisible();
  return state;
}

test("T-15-07 Sneha: click [2] → the panel shows the exact passage, highlighted", async ({ page, context }) => {
  await openReport(page, context);
  await expect(page.getByRole("region", { name: "Evidence summary" })).toContainText("Weak");

  await page.getByRole("button", { name: /^Citation 2, Weak/ }).first().click();
  const panel = page.getByTestId("citation-panel");
  await expect(panel).toBeVisible();
  await expect(panel.getByTestId("passage-highlight")).toHaveText(QUOTE);
  await expect(panel).toContainText("Item recovery study");
  await expect(panel).toContainText("Preprint (not peer-reviewed), 2024");
  await expect(panel).toContainText("Doesn't say"); // the other source's verdict
  await expect(panel.getByRole("button", { name: "Find better sources" })).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(panel).toBeHidden();
});

test("T-15-08 edit text around a citation, save, reload → the chip is still attached to the same claim", async ({ page, context }) => {
  const state = await openReport(page, context);
  const editor = page.getByRole("textbox", { name: "Key findings — text" });
  await expect(editor.locator('[data-claim-id="cl2"]')).toBeVisible();

  await editor.locator("p").first().click({ position: { x: 4, y: 8 } }); // on the text, not on the [2] chip
  await page.keyboard.press("Home");
  await page.keyboard.type("In one survey, ");
  await page.keyboard.press("End");
  await page.keyboard.type(" Results vary.");
  await page.keyboard.press("Control+s");
  await expect(page.locator('[data-section-key="key_findings"] [data-save-state]')).toHaveText("All changes saved");

  expect(state.sectionSaves).toHaveLength(1);
  expect(state.sectionSaves[0].ifMatch).toBe('"0"');
  const saved = JSON.stringify(state.sectionSaves[0].contentDoc);
  expect(saved).toContain('"claimId":"cl2"');
  expect(saved).toContain("In one survey, Some apps return");
  expect(saved).toContain("Results vary.");

  await page.reload();
  const after = page.getByRole("textbox", { name: "Key findings — text" });
  await expect(after).toContainText("In one survey, Some apps return about 40 percent of items.");
  await expect(after).toContainText("Results vary.");
  await expect(after.locator('[data-claim-id="cl2"]')).toHaveCount(1);

  // the save is a version (F-FR-6)
  await page.getByRole("button", { name: "Versions" }).click();
  await expect(page.getByRole("complementary", { name: "Versions" })).toContainText("your edit");
});
