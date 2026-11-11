import { describe, expect, it } from "vitest";

import { parseSites, sectionsError } from "../research-options";

// Same cases as the API's CustomRulesTest (T-10-09).
describe("T-10-09 parseSites", () => {
  it("turns URLs into host names, merges duplicates", () => {
    expect(parseSites("https://www.IEEE.org/xplore?q=1, ieee.org  arxiv.org\ndocs.spring.io:443")).toEqual({
      sites: ["ieee.org", "arxiv.org", "docs.spring.io"],
      error: null,
    });
    expect(parseSites("   ")).toEqual({ sites: [], error: null });
  });

  it.each(["127.0.0.1", "localhost", "http://169.254.169.254/", "a.b"])("rejects %s", (bad) => {
    expect(parseSites(bad).error).toContain("isn't a website name");
  });

  it("at most 10", () => {
    const eleven = Array.from({ length: 11 }, (_, i) => `site${i}.com`).join(",");
    expect(parseSites(eleven).error).toContain("At most 10");
  });
});

describe("T-10-09 sectionsError", () => {
  it("accepts distinct titles, rejects duplicates, short ones and too many", () => {
    expect(sectionsError(["Introduction", "Cost in India", "My pick"])).toBeNull();
    expect(sectionsError(["Intro", " intro "])).toContain("Two sections");
    expect(sectionsError(["A"])).toContain("2–80");
    expect(sectionsError([])).toContain("at least one");
    expect(sectionsError(Array.from({ length: 13 }, (_, i) => `Section ${i}`))).toContain("at most 12");
  });
});
