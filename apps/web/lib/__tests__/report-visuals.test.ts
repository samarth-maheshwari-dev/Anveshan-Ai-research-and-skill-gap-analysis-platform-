import { describe, expect, test } from "vitest";

import { countCitations, keyFindings, sourceTypes, sourceYears, topSources } from "@/lib/report";

describe("T-17-01 report visuals are counted by code", () => {
  test("sourceTypes groups by readable type, largest first", () => {
    const bins = sourceTypes([{ type: "PAPER" }, { type: "WEB" }, { type: "PAPER" }, {}]);
    expect(bins).toEqual([
      { key: "Research papers", label: "Research papers", value: 2 },
      { key: "Other", label: "Other", value: 1 },
      { key: "Web pages", label: "Web pages", value: 1 },
    ]);
  });

  test("sourceYears fills gaps and ignores undated sources", () => {
    const bins = sourceYears([{ publishedAt: "2021-03-01" }, { publishedAt: "2023-01-01" }, { publishedAt: "2023-05-01" }, {}]);
    expect(bins.map((b) => [b.label, b.value])).toEqual([
      ["2021", 1],
      ["2022", 0],
      ["2023", 2],
    ]);
    expect(sourceYears([{}])).toEqual([]);
  });

  test("topSources counts distinct claims per source", () => {
    const sources = { a: { title: "A" }, b: { title: "B" } };
    const cites = [
      { sourceId: "a", claimId: "1" },
      { sourceId: "a", claimId: "1" },
      { sourceId: "a", claimId: "2" },
      { sourceId: "b", claimId: "3" },
      { sourceId: "zzz", claimId: "4" },
    ];
    expect(topSources(cites, sources).map((b) => [b.label, b.value])).toEqual([
      ["A", 2],
      ["B", 1],
    ]);
  });

  test("countCitations walks the doc", () => {
    const doc = { type: "doc", content: [{ type: "paragraph", content: [{ type: "text" }, { type: "citation" }, { type: "citation" }] }] };
    expect(countCitations(doc)).toBe(2);
  });

  test("keyFindings keeps only supported claims, most cited first", () => {
    const claims = {
      x: { text: "Weak one", trustLabel: "WEAK" },
      y: { text: "Supported, cited once", trustLabel: "SUPPORTED" },
      z: { text: "Supported, cited twice", trustLabel: "SUPPORTED" },
    };
    const cites = [{ claimId: "z" }, { claimId: "z" }, { claimId: "y" }, { claimId: "x" }];
    expect(keyFindings(claims, cites).map((f) => f.id)).toEqual(["z", "y"]);
  });
});
