import { describe, expect, test } from "vitest";

import { timeAgo } from "@/lib/time";

const now = new Date("2026-11-11T12:00:00Z");

describe("timeAgo (dashboard activity)", () => {
  test("seconds, minutes, hours, days, then a date", () => {
    expect(timeAgo("2026-11-11T11:59:30Z", now)).toBe("just now");
    expect(timeAgo("2026-11-11T11:55:00Z", now)).toBe("5m ago");
    expect(timeAgo("2026-11-11T09:00:00Z", now)).toBe("3h ago");
    expect(timeAgo("2026-11-09T12:00:00Z", now)).toBe("2d ago");
    expect(timeAgo("2026-10-01T12:00:00Z", now)).toBe("1 Oct 2026");
  });

  test("a clock slightly ahead of the server never shows a negative time", () => {
    expect(timeAgo("2026-11-11T12:00:20Z", now)).toBe("just now");
  });
});
