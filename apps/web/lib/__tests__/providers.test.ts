import { describe, expect, test } from "vitest";

import { pickBanner } from "@/components/anveshan/banner";
import { ApiError } from "@/lib/api/errors";
import { describeTestError, preferredModel } from "@/lib/providers";

const ids = (...list: string[]) => list.map((id) => ({ id }));

describe("wizard: default model choice", () => {
  test("Gemini: a plain flash model, skipping embeddings, previews and lite variants", () => {
    const models = ids("embedding-001", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-2.5-flash-preview-09", "gemini-2.5-flash");
    expect(preferredModel("GEMINI", models)).toBe("gemini-2.5-flash");
  });

  test("Groq: a llama versatile/instant model; whisper and guard models never chosen", () => {
    expect(preferredModel("GROQ", ids("whisper-large-v3", "llama-guard-4", "llama-3.3-70b-versatile"))).toBe(
      "llama-3.3-70b-versatile",
    );
  });

  test("OpenRouter prefers a free model; falls back to the first chat model; none → undefined", () => {
    expect(preferredModel("OPENROUTER", ids("openai/gpt-4o", "meta/llama-3:free"))).toBe("meta/llama-3:free");
    expect(preferredModel("CUSTOM", ids("text-embedding-3", "my-chat"))).toBe("my-chat");
    expect(preferredModel("GEMINI", ids("text-embedding-004", "imagen-3"))).toBeUndefined();
  });
});

describe("wizard: plain-language errors (APPFLOW §4.3)", () => {
  const err = (status: number, code: string, detail: string) => new ApiError(status, { status, code, detail });

  test("key invalid → 'This key didn't work…' (no blame)", () => {
    const e = describeTestError(err(422, "CONNECTION_TEST_FAILED", "Key invalid"), "Google Gemini");
    expect(e.kind).toBe("key");
    expect(e.message).toBe("This key didn't work. Check that you copied the whole key.");
  });

  test("model not found → pick another model", () => {
    expect(describeTestError(err(422, "CONNECTION_TEST_FAILED", "Model not found"), "Groq").kind).toBe("model");
  });

  test("provider unreachable / 5xx → 'Couldn't reach <provider>…'", () => {
    const e = describeTestError(err(422, "CONNECTION_TEST_FAILED", "Could not reach the provider"), "Groq");
    expect(e).toEqual({ kind: "network", message: "Couldn't reach Groq right now. Try again in a minute." });
    expect(describeTestError(err(503, "UPSTREAM_UNAVAILABLE", "x"), "Groq").kind).toBe("network");
  });

  test("unsafe custom URL and browser network failure", () => {
    expect(describeTestError(err(422, "UNSAFE_URL", "Only https URLs are allowed"), "Custom").kind).toBe("url");
    expect(describeTestError(new TypeError("Failed to fetch"), "Groq").kind).toBe("network");
  });
});

describe("global banners (APPFLOW §2.2)", () => {
  test("#2 no verified connection beats #3 failing key", () => {
    expect(pickBanner(false, [{ label: "My Groq", status: "FAILING" }])?.text).toBe(
      "Connect an AI provider to start researching.",
    );
  });

  test("#3 names the failing key and links to Settings → AI", () => {
    const b = pickBanner(true, [{ label: "My Groq", status: "FAILING" }]);
    expect(b?.text).toBe("Your My Groq key stopped working.");
    expect(b?.action.href).toBe("/settings/ai");
  });

  test("all good, or still loading → no banner", () => {
    expect(pickBanner(true, [{ label: "x", status: "VERIFIED" }])).toBeNull();
    expect(pickBanner(undefined, undefined)).toBeNull();
  });
});
