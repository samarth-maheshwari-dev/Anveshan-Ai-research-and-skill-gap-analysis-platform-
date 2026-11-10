import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, test } from "vitest";

/**
 * RULES §0 rule 14 / T-08-08: UI uses tokens only. Colors live in app/globals.css; components must
 * not contain hex or rgb() colors, Tailwind palette colors (bg-red-500…) or arbitrary color values.
 */
const root = fileURLToPath(new URL("../../", import.meta.url));
const SCAN = ["app", "components", "lib"];
const ALLOWED = new Set([
  "app/globals.css", // the token source
  "app/layout.tsx", // <meta name="theme-color"> needs literal values (same tokens)
]);

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (name === "__tests__" || name === "node_modules") return [];
    return statSync(p).isDirectory() ? files(p) : /\.(tsx?|css)$/.test(name) ? [p] : [];
  });
}

const sources = SCAN.flatMap((d) => files(join(root, d)))
  .map((p) => ({ path: relative(root, p).replaceAll("\\", "/"), text: readFileSync(p, "utf8") }))
  .filter((f) => !ALLOWED.has(f.path) && !f.path.endsWith("schema.d.ts"));

const PALETTE = /\b(?:bg|text|border|ring|fill|stroke|from|to|via|outline|shadow)-(?:red|green|blue|yellow|amber|orange|lime|emerald|teal|cyan|sky|indigo|violet|purple|fuchsia|pink|rose|slate|gray|zinc|neutral|stone|black|white)(?:-\d{2,3})?\b/;

describe("T-08-08 no hard-coded colors outside globals.css", () => {
  test("there are files to scan", () => {
    expect(sources.length).toBeGreaterThan(10);
  });

  test.each(sources.map((s) => [s.path, s.text] as const))("%s uses tokens only", (_path, text) => {
    expect(text).not.toMatch(/#[0-9a-fA-F]{3,8}\b(?![\w-])/);
    expect(text).not.toMatch(/\brgba?\(/);
    expect(text).not.toMatch(PALETTE);
    expect(text).not.toMatch(/(?:bg|text|border)-\[#/);
  });
});
