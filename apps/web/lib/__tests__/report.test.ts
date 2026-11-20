import { describe, expect, test } from "vitest";

import {
  countLabels,
  insertableContent,
  labelExplanation,
  plainText,
  refreshStatusText,
  saveStateText,
  shares,
  splitHighlight,
} from "@/lib/report";

describe("Report Studio helpers (S-08)", () => {
  test("T-15-15 countLabels + shares: counts per label; whole percents that add up to 100", () => {
    const c = countLabels(["SUPPORTED", "WEAK", "WEAK", "CONFLICTING", undefined, null, "BOGUS"]);
    expect(c).toEqual({ SUPPORTED: 1, WEAK: 2, CONFLICTING: 1, NO_SOURCE: 0 });
    const s = shares({ SUPPORTED: 1, WEAK: 1, CONFLICTING: 1, NO_SOURCE: 0 });
    expect(s.SUPPORTED + s.WEAK + s.CONFLICTING + s.NO_SOURCE).toBe(100);
    expect(Object.values(s).sort()).toEqual([0, 33, 33, 34]);
    expect(shares({ SUPPORTED: 0, WEAK: 0, CONFLICTING: 0, NO_SOURCE: 0 })).toEqual({ SUPPORTED: 0, WEAK: 0, CONFLICTING: 0, NO_SOURCE: 0 });
  });

  test("T-15-16 splitHighlight: exact [start, end) span; broken offsets are clamped, never thrown", () => {
    const text = "Students lose items. 60% of students lose something each term.";
    const { before, mark, after } = splitHighlight(text, 21, 61);
    expect(mark).toBe("60% of students lose something each term");
    expect(before + mark + after).toBe(text);
    expect(splitHighlight("abc", -5, 99)).toEqual({ before: "", mark: "abc", after: "" });
    expect(splitHighlight("abc", 2, 1)).toEqual({ before: "ab", mark: "", after: "c" });
  });

  test("T-15-17 Assist suggestion: one paragraph inserts inline, more blocks insert as blocks; plain text shows [n]", () => {
    const para = { type: "paragraph", content: [{ type: "text", text: "Simple " }, { type: "citation", attrs: { n: 2 } }] };
    expect(insertableContent({ type: "doc", content: [para] })).toEqual(para.content);
    const two = { type: "doc", content: [para, { type: "paragraph" }] };
    expect(insertableContent(two)).toHaveLength(2);
    expect(insertableContent(null)).toEqual([]);
    expect(plainText({ type: "doc", content: [para, { type: "bulletList", content: [{ type: "listItem", content: [{ type: "paragraph", content: [{ type: "text", text: "x" }] }] }] }] }))
      .toBe("Simple [2]\nx");
    expect(plainText(undefined)).toBe("");
  });

  test("T-15-18 save and refresh states read as words", () => {
    expect(saveStateText("saved")).toBe("All changes saved");
    expect(saveStateText("dirty")).toBe("Unsaved changes");
    expect(saveStateText("saving")).toBe("Saving…");
    expect(saveStateText("conflict")).toContain("reload");
    expect(saveStateText("error")).toContain("Couldn't save");
    expect(refreshStatusText("RUNNING")).toBe("Looking for stronger sources…");
    expect(refreshStatusText("COMPLETED", "WEAK", "SUPPORTED")).toContain("label was updated");
    expect(refreshStatusText("COMPLETED", "WEAK", "WEAK")).toContain("No stronger sources");
    expect(refreshStatusText("FAILED")).toContain("stopped");
    expect(refreshStatusText("PAUSED")).toContain("AI limit");
  });

  test("T-15-25 labelExplanation: why a label, in words — own source alone is single-source, unchecked claims say so", () => {
    const own = { own: true, verdict: "SUPPORTS", credibilityTier: "HIGH" };
    expect(labelExplanation("WEAK", [own, { own: false, verdict: "INSUFFICIENT", credibilityTier: "HIGH" }])).toBe(
      "Only the source it came from says this. No other source in this research confirmed it yet.",
    );
    expect(labelExplanation("WEAK", [own, { own: false, verdict: "SUPPORTS", credibilityTier: "LOW" }])).toContain("low-credibility");
    expect(labelExplanation("WEAK", [{ own: true, verdict: "PARTIAL", credibilityTier: "HIGH" }])).toContain("partly");
    expect(labelExplanation("SUPPORTED", [own, { own: false, verdict: "SUPPORTS", credibilityTier: "MEDIUM" }])).toBe(
      "Confirmed by 1 other credible source, not only the one it came from.",
    );
    expect(
      labelExplanation("CONFLICTING", [own, { own: false, verdict: "CONTRADICTS", credibilityTier: "HIGH" }, { own: false, verdict: "CONTRADICTS", credibilityTier: "MEDIUM" }]),
    ).toContain("2 credible sources say the opposite");
    expect(labelExplanation("NO_SOURCE", [])).toContain("couldn't be checked");
    expect(labelExplanation("NO_SOURCE", undefined)).toContain("couldn't be checked");
    expect(labelExplanation("NO_SOURCE", [{ own: true, verdict: "INSUFFICIENT", credibilityTier: "HIGH" }])).toContain("unverified");
    expect(labelExplanation(undefined, [])).toBe("");
  });
});
