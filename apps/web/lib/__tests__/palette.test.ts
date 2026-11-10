import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, test } from "vitest";

/**
 * T-08-19 — the 60 · 30 · 10 palette (DESIGN §2.1, owner decision 2026-11-10), checked on the real
 * globals.css: black and white do the work, ONE calm green is the only accent, and no old hue
 * (indigo, saffron, cream, blue …) survives. Semantic status colors (trust labels, danger) are exempt.
 */
const css = readFileSync(fileURLToPath(new URL("../../app/globals.css", import.meta.url)), "utf8");

function tokens(selector: ":root" | ".dark"): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  const body = css.slice(start, css.indexOf("\n}", start));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})/g)].map((m) => [m[1]!, m[2]!]));
}

/** Hue (0–360), HSL saturation and chroma (max − min) of a #rrggbb color. */
function hsl(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255) as [number, number, number];
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const chroma = max - min;
  const l = (max + min) / 2;
  const s = chroma === 0 ? 0 : chroma / (1 - Math.abs(2 * l - 1));
  let h = 0;
  if (chroma > 0) {
    if (max === r) h = 60 * (((g - b) / chroma) % 6);
    else if (max === g) h = 60 * ((b - r) / chroma + 2);
    else h = 60 * ((r - g) / chroma + 4);
  }
  return { h: (h + 360) % 360, s, chroma, l };
}

const GREEN_ROLES = ["primary", "primary-hover", "primary-soft", "ring", "marker"];
const SEMANTIC = /^(trust-|danger)/;

describe.each([":root", ".dark"] as const)("T-08-19 palette in %s", (theme) => {
  const t = tokens(theme);

  test("every non-semantic, non-accent token is a pure neutral (black / white / grey)", () => {
    const chromatic = Object.entries(t).filter(
      ([name, hex]) => !GREEN_ROLES.includes(name) && !SEMANTIC.test(name) && hsl(hex).chroma > 0.02,
    );
    expect(chromatic, "only greys may carry the 90 %").toEqual([]);
  });

  test.each(GREEN_ROLES)("--%s is the one natural green: hue 110–165°, calm (saturation ≤ 0.6), never neon", (name) => {
    const c = hsl(t[name]!);
    expect(c.chroma, "visibly green").toBeGreaterThan(0.03);
    expect(c.h).toBeGreaterThanOrEqual(110);
    expect(c.h).toBeLessThanOrEqual(165);
    expect(c.s).toBeLessThanOrEqual(0.6);
  });
});

describe("T-08-19 black-first, and light is the exact inverse", () => {
  const dark = tokens(".dark");
  const light = tokens(":root");
  const lum = (hex: string) => hsl(hex).l;

  test("dark: black background and surfaces, white text", () => {
    for (const name of ["background", "surface", "surface-2"]) expect(lum(dark[name]!), name).toBeLessThan(0.12);
    expect(lum(dark["foreground"]!)).toBeGreaterThan(0.95);
  });

  test("light swaps the two: its background is dark's text and its text is dark's background", () => {
    expect(light["background"]).toBe(dark["foreground"]);
    expect(light["foreground"]).toBe(dark["background"]);
    expect(light["inverse"]).toBe(dark["background"]);
    expect(dark["inverse"]).toBe(dark["foreground"]);
  });

  test("no saffron, indigo or cream token is left over", () => {
    for (const gone of ["accent", "accent-foreground", "success"]) {
      expect(dark[gone], gone).toBeUndefined();
      expect(light[gone], gone).toBeUndefined();
    }
  });
});
