import { describe, expect, test } from "vitest";
import { cn } from "@/lib/utils";

describe("smoke", () => {
  test("T-01-04 cn() merges conflicting Tailwind classes", () => {
    expect(cn("p-2", "p-4")).toBe("p-4");
  });

  test("T-08 cn() keeps a type-scale size next to a token color (text-caption + text-trust-supported)", () => {
    expect(cn("text-caption font-medium", "text-trust-supported")).toBe("text-caption font-medium text-trust-supported");
    expect(cn("text-body", "text-body-sm")).toBe("text-body-sm");
    expect(cn("text-primary", "text-danger")).toBe("text-danger");
  });
});
