import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, test } from "vitest";

/**
 * T-08-06: every token pair in DESIGN §13.2 meets its WCAG 2.1 threshold, measured from the real
 * globals.css (so a token change that breaks contrast fails the build).
 */
const css = readFileSync(fileURLToPath(new URL("../../app/globals.css", import.meta.url)), "utf8");

function tokens(selector: ":root" | ".dark"): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  const body = css.slice(start, css.indexOf("\n}", start));
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})/g)) out[m[1]!] = m[2]!;
  return out;
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const lin = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * lin(r!) + 0.7152 * lin(g!) + 0.0722 * lin(b!);
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

/** [foreground token, background token, minimum ratio] — DESIGN §13.2. */
const PAIRS: [string, string, number][] = [
  ["foreground", "background", 4.5],
  ["foreground", "surface", 4.5],
  ["muted-foreground", "surface", 4.5],
  ["muted-foreground", "surface-2", 4.5],
  ["muted-foreground", "background", 4.5],
  ["subtle-foreground", "surface", 3.0],
  ["primary-foreground", "primary", 4.5],
  ["primary", "surface", 4.5],
  ["primary", "background", 4.5],
  ["primary", "primary-soft", 4.5],
  ["inverse-foreground", "inverse", 4.5],
  ["inverse-muted", "inverse", 4.5],
  ["foreground", "marker", 4.5],
  ["danger", "surface", 4.5],
  ["foreground", "danger-soft", 4.5],
  ["trust-supported", "trust-supported-bg", 4.5],
  ["trust-weak", "trust-weak-bg", 4.5],
  ["trust-conflict", "trust-conflict-bg", 4.5],
  ["trust-none", "trust-none-bg", 4.5],
  ["trust-approved", "trust-approved-bg", 4.5],
  ["input", "surface", 3.0],
  ["ring", "background", 3.0],
];

describe.each([":root", ".dark"] as const)("T-08-06 contrast in %s", (theme) => {
  const t = tokens(theme);

  test("all tokens used by the checks are defined as hex", () => {
    for (const [fg, bg] of PAIRS) {
      expect(t[fg], `--${fg}`).toMatch(/^#[0-9a-fA-F]{6}$/);
      expect(t[bg], `--${bg}`).toMatch(/^#[0-9a-fA-F]{6}$/);
    }
  });

  test.each(PAIRS)("%s on %s ≥ %s:1", (fg, bg, min) => {
    expect(contrast(t[fg]!, t[bg]!)).toBeGreaterThanOrEqual(min);
  });
});

describe("contrast() is the WCAG formula and matches DESIGN §13.2's published numbers", () => {
  test("canonical WCAG values: black on white = 21, white on white = 1", () => {
    expect(contrast("#000000", "#FFFFFF")).toBeCloseTo(21, 2);
    expect(contrast("#FFFFFF", "#FFFFFF")).toBeCloseTo(1, 5);
  });

  test("light foreground on background = 18.97, Supported = 5.74; dark primary button text = 9.85", () => {
    const light = tokens(":root");
    const dark = tokens(".dark");
    expect(contrast(light["foreground"]!, light["background"]!)).toBeCloseTo(18.97, 1);
    expect(contrast(light["trust-supported"]!, light["trust-supported-bg"]!)).toBeCloseTo(5.74, 1);
    expect(contrast(dark["primary-foreground"]!, dark["primary"]!)).toBeCloseTo(9.85, 1);
  });
});
