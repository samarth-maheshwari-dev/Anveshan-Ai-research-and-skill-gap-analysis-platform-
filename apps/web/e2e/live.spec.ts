import { expect, test } from "@playwright/test";

import { fakeRun, installFakeApi, verifiedConnection, type FakeRun } from "./fake-api";

const ROUTED = {
  PLAN: [{ connectionId: "c-existing", model: "m" }],
  WRITE: [{ connectionId: "c-existing", model: "m" }],
};

function liveRun(overrides: Partial<FakeRun> = {}): FakeRun {
  return fakeRun({
    id: "r-live",
    status: "RUNNING",
    sources: [
      { sourceId: "src1", title: "Lost and found apps compared", site: "campus.example", url: "https://campus.example/a",
        type: "OTHER", tier: "LOW", tierReason: "Personal blog / unknown author", status: "USED", claims: 3 },
      { sourceId: "src2", title: "Item recovery study", site: "study.example", url: "https://study.example/b",
        type: "PREPRINT", tier: "MEDIUM", tierReason: "Preprint (not peer-reviewed), 2024", status: "USED", claims: 2 },
    ],
    events: [
      { id: 1, type: "log", data: { level: "INFO", message: "Found 6 candidate sources for \"How do apps help?\"." } },
      { id: 2, type: "source.added", data: { sourceId: "src1", title: "Lost and found apps compared" } },
      { id: 3, type: "source.added", data: { sourceId: "src2", title: "Item recovery study" } },
      { id: 4, type: "log", data: { level: "WARN", message: "1 page couldn't be opened — The site doesn't allow automated reading (robots.txt)." } },
    ],
    ...overrides,
  });
}

test("T-14-06 Live Room: sources appear, [Skip] one, [Pause], [Resume] → report ready → report page", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [liveRun({ completeOnResume: true })],
  });
  await page.goto("/research/r-live/live");

  await expect(page.getByTestId("live-source")).toHaveCount(2);
  await expect(page.getByText("Medium credibility")).toBeVisible();
  await expect(page.getByTestId("live-log")).toContainText("Found 6 candidate sources");

  await page.getByRole("button", { name: "Skip Item recovery study" }).click();
  await expect(page.getByText("Skipped")).toBeVisible();
  expect(api.skipped).toEqual(["src2"]);

  await page.getByRole("button", { name: "Pause" }).click();
  await expect(page.getByText("Paused", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Resume" }).click();

  await expect(page.getByText("Report ready. Opening it in a moment…")).toBeVisible();
  await expect(page).toHaveURL(/\/research\/r-live\/report$/, { timeout: 10_000 });
  await expect(page.getByRole("heading", { name: "Key findings" })).toBeVisible();
  await expect(page.getByLabel("Citation 2, Weak evidence")).toBeVisible();
  await expect(page.getByText("Weak evidence", { exact: true }).first()).toBeVisible();
  expect(api.actions).toEqual(["pause", "resume"]);
});

test("T-14-07 close the tab mid-run and reopen: the run kept going, the earlier log is replayed", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [liveRun()],
  });
  await page.goto("/research/r-live/live");
  await expect(page.getByTestId("live-log")).toContainText("Found 6 candidate sources");
  await page.close();

  // meanwhile the run went on
  const run = api.runs[0];
  run.events!.push({ id: 5, type: "log", data: { level: "INFO", message: "Extracted 7 claims from 4 passages." } });
  run.events!.push({ id: 6, type: "log", data: { level: "INFO", message: "Labelled 7 claims: 5 supported, 2 weak, 0 conflicting, 0 with no source." } });

  const again = await context.newPage();
  await again.goto("/research/r-live/live");
  const log = again.getByTestId("live-log");
  await expect(log).toContainText("Found 6 candidate sources");
  await expect(log).toContainText("Extracted 7 claims from 4 passages.");
  await expect(log).toContainText("Labelled 7 claims");
  expect(api.eventRequests.at(-1)).toBe(0); // a new tab replays from the start
});

test("Stop asks first; a stopped run is read-only", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [liveRun()],
  });
  await page.goto("/research/r-live/live");
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await expect(page.getByText("Stop this research? You can still see what was found.")).toBeVisible();
  await page.getByRole("button", { name: "Keep going" }).click();
  expect(api.actions).toEqual([]);
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await page.getByRole("button", { name: "Stop research" }).click();
  await expect(page.getByText("This research was stopped.")).toBeVisible();
  await expect(page.getByRole("button", { name: /^Skip / })).toHaveCount(0);
});

test("Dashboard lists the user's researches and opens the right screen", async ({ page, context }) => {
  await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [liveRun(), fakeRun({ id: "r-done", status: "COMPLETED", reportId: "rep-r-done", topic: "Java full stack roadmap for 2027" })],
  });
  await page.goto("/dashboard");
  await expect(page.getByRole("link", { name: /Java full stack roadmap for 2027/ })).toContainText("Report ready");
  await page.getByRole("link", { name: /Java full stack roadmap for 2027/ }).click();
  await expect(page).toHaveURL(/\/research\/r-done\/report$/);
  await expect(page.getByRole("heading", { name: "References" })).toBeVisible();
});
