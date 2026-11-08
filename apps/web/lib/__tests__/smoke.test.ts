import { describe, expect, test } from "vitest";
import { cn } from "@/lib/utils";

describe("smoke", () => {
  test("T-01-04 cn() merges conflicting Tailwind classes", () => {
    expect(cn("p-2", "p-4")).toBe("p-4");
  });
});
