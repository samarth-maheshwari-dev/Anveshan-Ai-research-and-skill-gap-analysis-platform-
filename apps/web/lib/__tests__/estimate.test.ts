import { describe, expect, it } from "vitest";

import { estimate, sizeOf, type Depth } from "../estimate";

const preset = (d: Depth) => sizeOf(d)!;

// Same cases as the API's EstimateTest / CustomRulesTest: the browser and the server must agree.
describe("T-10-01 estimate (mirror of Estimate.of)", () => {
  it.each([
    ["QUICK", 3, 5, 1, 5, 3, 5],
    ["STANDARD", 5, 5, 1, 12, 7, 15],
    ["DEEP", 6, 6, 1, 24, 14, 30],
    ["STANDARD", 1, 5, 1, 3, 2, 3],
    ["STANDARD", 8, 5, 1, 20, 11, 24],
  ] as const)("%s with %i sub-questions", (depth, subQ, sections, plan, extract, verify, minutes) => {
    const e = estimate(preset(depth), subQ, sections)!;
    expect(e.requests).toEqual({ PLAN: plan, EXTRACT: extract, VERIFY: verify, WRITE: sections + 1 });
    expect(e.total).toBe(plan + extract + verify + sections + 1);
    expect(e.minutes).toBe(minutes);
  });

  it("deleting a card lowers the estimate", () => {
    expect(estimate(preset("STANDARD"), 4, 5)!.total).toBeLessThan(estimate(preset("STANDARD"), 5, 5)!.total);
  });

  it("a cache hit rate lowers EXTRACT only", () => {
    const e = estimate(preset("STANDARD"), 5, 5, 0.5)!;
    expect(e.requests.EXTRACT).toBe(6);
    expect(e.requests.VERIFY).toBe(7);
  });

  it("0 or 9 sub-questions has no estimate", () => {
    expect(estimate(preset("STANDARD"), 0, 5)).toBeNull();
    expect(estimate(preset("STANDARD"), 9, 5)).toBeNull();
  });
});

describe("T-10-10 custom depth (mirror of ResearchSize.of)", () => {
  it("own numbers drive the estimate", () => {
    const size = sizeOf("CUSTOM", { subQuestions: 2, sourcesPerQuestion: 10 })!;
    expect(size.subQuestions).toBe(2);
    const e = estimate(size, 2, 3)!;
    expect(e.requests).toEqual({ PLAN: 1, EXTRACT: 8, VERIFY: 5, WRITE: 4 });
    expect(e.minutes).toBe(10);
  });

  it("presets ignore custom numbers; out of range → null", () => {
    expect(sizeOf("STANDARD", { subQuestions: 2, sourcesPerQuestion: 10 })).toEqual(preset("STANDARD"));
    expect(sizeOf("CUSTOM", { subQuestions: 9 })).toBeNull();
    expect(sizeOf("CUSTOM", { sourcesPerQuestion: 1 })).toBeNull();
    expect(sizeOf("CUSTOM", { subQuestions: 2.5 })).toBeNull();
  });
});
