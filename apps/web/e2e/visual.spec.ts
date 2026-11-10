import { expect, test } from "@playwright/test";

import { installFakeApi, verifiedConnection } from "./fake-api";

/**
 * T-08-08 support: every P08 screen in light + dark at 1440 px and 390 px. Saves screenshots to
 * test-results/screens for a human review, and checks what a machine can: no horizontal scroll,
 * the theme class applied, and the page background is the theme's base color (globals.css).
 */
const SCREENS = [
  { name: "landing", path: "/", signedIn: false },
  { name: "login", path: "/login", signedIn: false },
  { name: "wizard", path: "/onboarding/connect-ai", signedIn: true },
  { name: "dashboard", path: "/dashboard", signedIn: true },
  { name: "settings-ai", path: "/settings/ai", signedIn: true },
] as const;

const BG = { light: "rgb(250, 250, 250)", dark: "rgb(10, 10, 10)" };

for (const theme of ["light", "dark"] as const) {
  for (const width of [1440, 390]) {
    for (const screen of SCREENS) {
      test(`T-08-08 ${screen.name} · ${theme} · ${width}px`, async ({ page, context }) => {
        await page.setViewportSize({ width, height: width > 500 ? 900 : 844 });
        // The user's choice in the menu (next-themes stores it as "theme"); dark is the default.
        await page.addInitScript((t) => window.localStorage.setItem("theme", t), theme);
        await installFakeApi(page, context, {
          signedIn: screen.signedIn,
          connections: screen.signedIn ? [verifiedConnection({ label: "My Gemini", provider: "GEMINI" })] : [],
          slots: screen.signedIn ? { PLAN: [{ connectionId: "c-existing", model: "gemini-2.5-flash" }] } : {},
        });
        await page.goto(screen.path);
        await page.waitForLoadState("networkidle");
        await expect(page.locator("main").first()).toBeVisible();

        expect(await page.evaluate(() => document.documentElement.classList.contains("dark"))).toBe(theme === "dark");
        expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(BG[theme]);
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        expect(overflow, "no horizontal scroll").toBeLessThanOrEqual(0);

        await page.screenshot({ path: `test-results/screens/${screen.name}-${theme}-${width}.png`, fullPage: true });
      });
    }
  }
}

test("T-08-19 black-first: a first visit is dark even when the OS prefers light", async ({ page, context }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await installFakeApi(page, context);
  await page.goto("/");
  await page.waitForLoadState("networkidle");
  expect(await page.evaluate(() => document.documentElement.classList.contains("dark"))).toBe(true);
  expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(BG.dark);
});
