import { expect, test, type Page } from "@playwright/test";

import { GOOD_KEY, installFakeApi } from "./fake-api";

/** Presses Tab until `target` has focus (max 25 presses); fails if it's unreachable. */
async function tabTo(page: Page, name: RegExp | string, role: "link" | "button" | "textbox" = "button") {
  const target = page.getByRole(role, { name });
  for (let i = 0; i < 25; i++) {
    if (await target.evaluate((el) => el === document.activeElement).catch(() => false)) return target;
    await page.keyboard.press("Tab");
  }
  throw new Error(`${String(name)} not reachable with Tab`);
}

async function expectVisibleFocus(page: Page) {
  const outline = await page.evaluate(() => {
    const el = document.activeElement as HTMLElement;
    const s = getComputedStyle(el);
    return { style: s.outlineStyle, width: parseFloat(s.outlineWidth) };
  });
  expect(outline.style).not.toBe("none");
  expect(outline.width).toBeGreaterThanOrEqual(2);
}

test("T-08-07 keyboard only: login page controls reachable with a visible focus ring", async ({ page, context }) => {
  await installFakeApi(page, context);
  await page.goto("/login");
  await tabTo(page, "Continue with Google", "link");
  await expectVisibleFocus(page);
  await tabTo(page, "Continue with GitHub", "link");
  await expectVisibleFocus(page);
});

test("T-08-07 keyboard only: the whole wizard can be completed without a mouse", async ({ page, context }) => {
  await installFakeApi(page, context, { signedIn: true });
  await page.goto("/onboarding/connect-ai");

  await tabTo(page, /Google Gemini/);
  await expectVisibleFocus(page);
  await page.keyboard.press("Enter");

  await tabTo(page, "I have my key");
  await expectVisibleFocus(page);
  await page.keyboard.press("Enter");

  await tabTo(page, "API key", "textbox");
  await page.keyboard.type(GOOD_KEY);
  await page.keyboard.press("Enter"); // submits the form

  await expect(page.getByRole("heading", { name: /is ready/ })).toBeVisible();
  await tabTo(page, "Start researching");
  await expectVisibleFocus(page);
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/dashboard$/);
});

test("T-08-07 skip link and user menu work from the keyboard; Esc closes the menu", async ({ page, context }) => {
  await installFakeApi(page, context, { signedIn: true });
  await page.addInitScript(() => window.sessionStorage.setItem("anv.onboarding.offered", "1"));
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /Welcome/ })).toBeVisible(); // shell rendered after /me
  await page.waitForLoadState("networkidle"); // banners/connections settled, nothing re-renders under the cursor
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();

  await tabTo(page, "Account menu");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("menu")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toHaveCount(0);
});
