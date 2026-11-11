import { describe, expect, it } from "vitest";

import { estimate } from "../estimate";

// Same cases as the API's EstimateTest (T-10-01): the browser and the server must agree.
describe("T-10-01 estimate (mirror of Estimate.of)", () => {
  it.each([
    ["QUICK", 3, 5, 1, 5, 3, 5],
    ["STANDARD", 5, 5, 1, 12, 7, 15],
    ["DEEP", 6, 6, 1, 24, 14, 30],
    ["STANDARD", 1, 5, 1, 3, 2, 3],
    ["STANDARD", 8, 5, 1, 20, 11, 24],
  ] as const)("%s with %i sub-questions", (depth, subQ, sections, plan, extract, verify, minutes) => {
    const e = estimate(depth, subQ, sections)!;
    expect(e.requests).toEqual({ PLAN: plan, EXTRACT: extract, VERIFY: verify, WRITE: sections });
    expect(e.total).toBe(plan + extract + verify + sections);
    expect(e.minutes).toBe(minutes);
  });

  it("deleting a card lowers the estimate", () => {
    expect(estimate("STANDARD", 4, 5)!.total).toBeLessThan(estimate("STANDARD", 5, 5)!.total);
  });

  it("a cache hit rate lowers EXTRACT only", () => {
    const e = estimate("STANDARD", 5, 5, 0.5)!;
    expect(e.requests.EXTRACT).toBe(6);
    expect(e.requests.VERIFY).toBe(7);
  });

  it("0 or 9 sub-questions has no estimate", () => {
    expect(estimate("STANDARD", 0, 5)).toBeNull();
    expect(estimate("STANDARD", 9, 5)).toBeNull();
  });
});
