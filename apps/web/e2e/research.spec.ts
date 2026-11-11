import { expect, test } from "@playwright/test";

import { fakeRun, installFakeApi, verifiedConnection } from "./fake-api";

const ROUTED = {
  PLAN: [{ connectionId: "c-existing", model: "llama-3.3-70b-versatile" }],
  WRITE: [{ connectionId: "c-existing", model: "llama-3.3-70b-versatile" }],
};

test("T-10-07 template → topic → start → delete a card → estimate drops → approve → live room", async ({ page, context }) => {
  const api = await installFakeApi(page, context, { signedIn: true, connections: [verifiedConnection()], slots: ROUTED });
  await page.goto("/dashboard");
  await page.getByRole("main").getByRole("link", { name: "New research" }).click();
  await expect(page).toHaveURL(/\/research\/new$/);

  // Step 1: template + topic (too short first: stays on the step with a message)
  await page.getByText("Competitor analysis").click();
  await page.getByLabel("What do you want to research?").fill("Apps");
  await page.getByRole("button", { name: "Next" }).click();
  await expect(page.getByText("Write at least 10 characters.")).toBeVisible();
  await page.getByLabel("What do you want to research?").fill("Compare lost-and-found apps for college campuses");
  await page.getByRole("button", { name: "Next" }).click();

  // Step 2–3: the template pre-filled them
  await expect(page.getByRole("radio", { name: /Comparison/ })).toBeChecked();
  await page.getByRole("button", { name: "Next" }).click();
  await expect(page.getByRole("checkbox", { name: "Blogs" })).toBeChecked();
  await page.getByRole("button", { name: "Next" }).click();

  // Step 4: review with the estimate line, then start
  await expect(page.getByText("Competitor analysis")).toBeVisible();
  await expect(page.getByText(/AI requests on your own keys/)).toBeVisible();
  await page.getByRole("button", { name: "Start research" }).click();

  await expect(page).toHaveURL(/\/research\/r\d+\/plan$/);
  await expect(page.getByRole("heading", { name: "Review your plan" })).toBeVisible();
  await expect(page.getByTestId("plan-card")).toHaveCount(5);
  const total = page.getByTestId("estimate-total");
  await expect(total).toHaveText("25"); // STANDARD, 5 sub-questions, 5 sections: 1 + 12 + 7 + 5

  await page.getByRole("button", { name: "Delete sub-question 5" }).click();
  await expect(page.getByTestId("plan-card")).toHaveCount(4);
  await expect(total).toHaveText("22"); // 1 + 10 + 6 + 5

  await page.getByRole("button", { name: "Approve and start" }).click();
  await expect(page).toHaveURL(/\/research\/r\d+\/live$/);
  await expect(page.getByText("Running", { exact: true })).toBeVisible();

  const run = api.runs[0];
  expect(run.subQuestions).toHaveLength(4);
  expect(run.status).toBe("RUNNING");
  expect(api.runKeys).toHaveLength(1);
  expect(api.runKeys[0]).toMatch(/^[0-9a-f-]{36}$/);
  expect(api.calls).toContain(`PUT /runs/${run.id}/plan`);
  expect(api.writesWithoutCsrf).toEqual([]);
});

test("T-10-08 /plan on a COMPLETED run redirects to the report", async ({ page, context }) => {
  await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [fakeRun({ id: "r-done", status: "COMPLETED" })],
  });
  await page.goto("/research/r-done/plan");
  await expect(page).toHaveURL(/\/research\/r-done\/report$/);
  await expect(page.getByRole("heading", { name: "Research complete" })).toBeVisible();
});

test("/research/[id] routes by status: AWAITING_APPROVAL → plan, RUNNING → live", async ({ page, context }) => {
  await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [fakeRun({ id: "r-wait" }), fakeRun({ id: "r-run", status: "RUNNING" })],
  });
  await page.goto("/research/r-wait");
  await expect(page).toHaveURL(/\/research\/r-wait\/plan$/);
  await page.goto("/research/r-run");
  await expect(page).toHaveURL(/\/research\/r-run\/live$/);
});

test("C-FR-3: without a PLAN/WRITE model the form explains and can't start", async ({ page, context }) => {
  await installFakeApi(page, context, { signedIn: true, connections: [], slots: {} });
  await page.goto("/research/new");
  await expect(page.getByText(/Research needs an AI model for planning and writing/)).toBeVisible();
  await expect(page.locator("#main").getByRole("link", { name: "Connect AI" })).toHaveAttribute("href", "/onboarding/connect-ai");
  await page.getByLabel("What do you want to research?").fill("Compare lost-and-found apps for college campuses");
  for (let i = 0; i < 3; i++) await page.getByRole("button", { name: "Next" }).click();
  await expect(page.getByRole("button", { name: "Start research" })).toBeDisabled();
});

test("D-FR-3: a plan changed in another tab shows Reload instead of overwriting", async ({ page, context }) => {
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [fakeRun({ id: "r-conflict" })],
  });
  await page.goto("/research/r-conflict/plan");
  await expect(page.getByTestId("plan-card")).toHaveCount(5);
  api.runs[0].version += 1; // someone saved meanwhile
  await page.getByRole("button", { name: "Delete sub-question 1" }).click();
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByText(/This plan changed in another tab/)).toBeVisible();
  await page.getByRole("button", { name: "Reload" }).click();
  await expect(page.getByTestId("plan-card")).toHaveCount(5);
  expect(api.runs[0].status).toBe("AWAITING_APPROVAL");
});

test("T-10-13 every choice has a 'your own' option: objective, depth, format, sections, sites, year, instructions", async ({ page, context }) => {
  const api = await installFakeApi(page, context, { signedIn: true, connections: [verifiedConnection()], slots: ROUTED });
  await page.goto("/research/new");
  await page.getByLabel("What do you want to research?").fill("Compare lost-and-found apps for college campuses");
  await page.getByLabel("Anything else the AI should know? (optional)").fill("Focus on India, for 2nd-year students");
  await page.getByRole("button", { name: "Next" }).click();

  // Objective: own words are required once "Something else" is picked
  await page.getByText("Something else").click();
  await page.getByText("Custom", { exact: true }).click();
  await page.getByRole("button", { name: "Next" }).click();
  await expect(page.getByText("Describe your objective in a few words.")).toBeVisible();
  await page.getByLabel("Your objective").fill("Hackathon pitch");
  await page.getByLabel("Sub-questions", { exact: true }).fill("2");
  await page.getByLabel("Sources per sub-question", { exact: true }).fill("10");
  await page.getByLabel("Report format").selectOption("CUSTOM");
  await page.getByLabel("Describe the format").fill("Slide notes");
  await page.getByText("My own sections").click();
  await expect(page.getByLabel("Section 1 title")).toHaveValue("Introduction");
  await page.getByLabel("Section 1 title").fill("Problem");
  await page.getByRole("button", { name: "Delete section 5" }).click();
  await page.getByRole("button", { name: "Delete section 4" }).click();
  await page.getByRole("button", { name: "Next" }).click();

  // Sources: a bad website name is caught next to the field
  await page.getByLabel("Prefer these websites (optional)").fill("ieee.org, localhost");
  await page.getByRole("button", { name: "Next" }).click();
  await expect(page.getByText(/"localhost" isn't a website name/)).toBeVisible();
  await page.getByLabel("Prefer these websites (optional)").fill("https://www.ieee.org/x, arxiv.org");
  await page.getByLabel("Avoid these websites (optional)").fill("quora.com");
  await page.getByLabel("Time range").selectOption("SINCE_YEAR");
  await page.getByLabel("From year").fill("2022");
  await page.getByRole("button", { name: "Next" }).click();

  await expect(page.getByText("Hackathon pitch · 2 sub-questions × 10 sources · Slide notes")).toBeVisible();
  await expect(page.getByText("Prefer: ieee.org, arxiv.org")).toBeVisible();
  await page.getByRole("button", { name: "Start research" }).click();
  await expect(page).toHaveURL(/\/research\/r\d+\/plan$/);

  expect(api.runBodies[0]).toMatchObject({
    objective: "OTHER",
    objectiveText: "Hackathon pitch",
    depth: "CUSTOM",
    subQuestions: 2,
    sourcesPerQuestion: 10,
    outputStyle: "CUSTOM",
    outputStyleText: "Slide notes",
    timeRange: "SINCE_YEAR",
    sinceYear: 2022,
    instructions: "Focus on India, for 2nd-year students",
    includeSites: ["ieee.org", "arxiv.org"],
    excludeSites: ["quora.com"],
  });
  expect((api.runBodies[0].sections as string[])[0]).toBe("Problem");
  expect(api.runBodies[0].sections).toHaveLength(3);
});

test("T-10-14 a failed plan shows a plain reason and 'Start again' re-creates the run with the same options", async ({ page, context }) => {
  const reason = "Your AI provider didn't answer — it was busy or down.";
  const api = await installFakeApi(page, context, {
    signedIn: true,
    connections: [verifiedConnection()],
    slots: ROUTED,
    runs: [
      fakeRun({
        id: "r-failed",
        status: "FAILED",
        topic: "Java full stack roadmap for 2027",
        config: { depth: "QUICK", sources: ["WEB", "BLOGS"] },
        planError: reason,
      }),
    ],
  });
  await page.goto("/research/r-failed");
  await expect(page).toHaveURL(/\/research\/r-failed\/live$/);
  await expect(page.getByText(reason)).toBeVisible();
  await expect(page.locator("body")).not.toContainText("Exception");
  await page.getByRole("button", { name: "Start again" }).click();
  await expect(page).toHaveURL(/\/research\/r\d+\/plan$/);
  expect(api.runBodies[0]).toMatchObject({ topic: "Java full stack roadmap for 2027", depth: "QUICK", sources: ["WEB", "BLOGS"] });
});
