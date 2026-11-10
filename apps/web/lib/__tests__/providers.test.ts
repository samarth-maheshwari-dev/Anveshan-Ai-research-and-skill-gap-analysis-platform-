import { describe, expect, test } from "vitest";

import { pickBanner } from "@/components/anveshan/banner";
import { ApiError } from "@/lib/api/errors";
import { describeTestError, providerName } from "@/lib/providers";

const failed = (reason: string, detail = "whatever the API says") =>
  new ApiError(422, { status: 422, code: "CONNECTION_TEST_FAILED", detail, reason });
const err = (status: number, code: string, detail: string) => new ApiError(status, { status, code, detail });

describe("T-08-18 wizard: plain-language errors from the API's machine-readable reason (APPFLOW §4.3)", () => {
  test("KEY_INVALID → 'This key didn't work…' (no blame); kind 'key' clears the input", () => {
    expect(describeTestError(failed("KEY_INVALID"), "Google Gemini")).toEqual({
      kind: "key",
      message: "This key didn't work. Check that you copied the whole key.",
    });
  });

  test("KEY_UNREADABLE and NO_CREDIT are key problems too, worded for what happened", () => {
    expect(describeTestError(failed("KEY_UNREADABLE"), "Groq")).toEqual({
      kind: "key",
      message: "The saved key can't be read anymore. Paste it again.",
    });
    expect(describeTestError(failed("NO_CREDIT"), "OpenAI").message).toBe(
      "Your OpenAI account has no credit left. Add credit or use a free provider.",
    );
  });

  test("MODEL_NOT_FOUND / NO_CHAT_MODEL / PROVIDER_ERROR → 'model' (offer the picker); the key is not blamed", () => {
    for (const reason of ["MODEL_NOT_FOUND", "NO_CHAT_MODEL", "PROVIDER_ERROR"]) {
      const e = describeTestError(failed(reason), "Google Gemini");
      expect(e.kind, reason).toBe("model");
      expect(e.message, reason).not.toMatch(/didn't work/);
    }
    expect(describeTestError(failed("NO_CHAT_MODEL"), "Google Gemini").message).toBe(
      "Your key works, but we couldn't find a model it can use. Choose one from the model list.",
    );
  });

  test("T-08-27 Settings has no model picker: model errors there point to Task routing, never to 'the list'", () => {
    for (const reason of ["MODEL_NOT_FOUND", "NO_CHAT_MODEL", "PROVIDER_ERROR"]) {
      const msg = describeTestError(failed(reason), "Groq", "settings").message;
      expect(msg, reason).toMatch(/Task routing/);
      expect(msg, reason).not.toMatch(/below|model list/);
    }
  });

  test("T-08-27 wizard with no model list (typed field): model errors say 'Type a model name below.'", () => {
    for (const reason of ["MODEL_NOT_FOUND", "NO_CHAT_MODEL", "PROVIDER_ERROR"]) {
      const msg = describeTestError(failed(reason), "Custom", "wizard-typed").message;
      expect(msg, reason).toMatch(/Type a model name below\.$/);
      expect(msg, reason).not.toMatch(/model list|Task routing/);
    }
  });

  test("the API's detail wording is never parsed: same detail, different reason → different result", () => {
    expect(describeTestError(failed("MODEL_NOT_FOUND", "Key invalid"), "Groq").kind).toBe("model");
    expect(describeTestError(failed("KEY_INVALID", "Model not found"), "Groq").kind).toBe("key");
  });

  test("provider unreachable / our 5xx → 'Couldn't reach <provider>…'", () => {
    expect(describeTestError(failed("PROVIDER_UNAVAILABLE"), "Groq")).toEqual({
      kind: "network",
      message: "Couldn't reach Groq right now. Try again in a minute.",
    });
    expect(describeTestError(err(503, "UPSTREAM_UNAVAILABLE", "x"), "Groq").kind).toBe("network");
  });

  test("unsafe custom URL, bad input, our own test rate limit, browser offline, unknown", () => {
    expect(describeTestError(err(422, "UNSAFE_URL", "Only https URLs are allowed"), "Custom").kind).toBe("url");
    expect(describeTestError(err(400, "VALIDATION_FAILED", "The API key looks wrong."), "Groq")).toEqual({
      kind: "key",
      message: "The API key looks wrong.",
    });
    expect(describeTestError(err(429, "RATE_LIMITED", "x"), "Groq").message).toBe(
      "Too many tests in the last hour. Try again later.",
    );
    expect(describeTestError(new TypeError("Failed to fetch"), "Groq").kind).toBe("network");
    expect(describeTestError(failed("SOMETHING_NEW"), "Groq").kind).toBe("other");
  });

  test("messages name the provider the way people know it, not the enum", () => {
    expect(providerName("GEMINI")).toBe("Google Gemini");
    expect(providerName("TAVILY")).toBe("Tavily");
    expect(providerName(undefined)).toBe("the provider");
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
    expect(b?.text).toBe("The key “My Groq” stopped working.");
    expect(b?.action.href).toBe("/settings/ai");
  });

  test("all good, or still loading → no banner", () => {
    expect(pickBanner(true, [{ label: "x", status: "VERIFIED" }])).toBeNull();
    expect(pickBanner(undefined, undefined)).toBeNull();
  });
});
