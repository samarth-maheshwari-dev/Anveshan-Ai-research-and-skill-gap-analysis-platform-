import { ApiError } from "@/lib/api/errors";
import type { Provider } from "@/lib/api/hooks/connections";

export type ProviderInfo = {
  id: Provider;
  name: string;
  tagline: string;
  free: boolean;
  keyUrl?: string;
  keyUrlLabel?: string;
  steps: string[];
  needsBaseUrl?: boolean;
};

/** Wizard catalogue (APPFLOW §4.2 step 5–6). Instructions, not marketing; verify links if a provider moves. */
export const PROVIDERS: Record<Exclude<Provider, "TAVILY">, ProviderInfo> = {
  GEMINI: {
    id: "GEMINI",
    name: "Google Gemini",
    tagline: "Recommended",
    free: true,
    keyUrl: "https://aistudio.google.com/app/apikey",
    keyUrlLabel: "Open Google AI Studio",
    steps: [
      "Open Google AI Studio and sign in with your Google account.",
      "Select “Create API key”.",
      "Copy the key (it starts with “AIza”).",
      "Come back here and paste it.",
    ],
  },
  GROQ: {
    id: "GROQ",
    name: "Groq",
    tagline: "Very fast",
    free: true,
    keyUrl: "https://console.groq.com/keys",
    keyUrlLabel: "Open Groq console",
    steps: [
      "Open the Groq console and sign in.",
      "Select “Create API Key” and give it a name.",
      "Copy the key (it starts with “gsk_”) — Groq shows it only once.",
      "Come back here and paste it.",
    ],
  },
  OPENROUTER: {
    id: "OPENROUTER",
    name: "OpenRouter",
    tagline: "Many models, some free",
    free: true,
    keyUrl: "https://openrouter.ai/keys",
    keyUrlLabel: "Open OpenRouter keys",
    steps: ["Open OpenRouter and sign in.", "Create a key.", "Copy it and paste it here."],
  },
  OPENAI: {
    id: "OPENAI",
    name: "OpenAI",
    tagline: "Paid account",
    free: false,
    keyUrl: "https://platform.openai.com/api-keys",
    keyUrlLabel: "Open OpenAI keys",
    steps: ["Open the OpenAI platform and sign in.", "Create a secret key.", "Copy it and paste it here."],
  },
  ANTHROPIC: {
    id: "ANTHROPIC",
    name: "Anthropic",
    tagline: "Paid account",
    free: false,
    keyUrl: "https://console.anthropic.com/settings/keys",
    keyUrlLabel: "Open Anthropic console",
    steps: ["Open the Anthropic console and sign in.", "Create a key.", "Copy it and paste it here."],
  },
  CUSTOM: {
    id: "CUSTOM",
    name: "Custom (OpenAI-compatible)",
    tagline: "Your own https endpoint",
    free: false,
    needsBaseUrl: true,
    steps: ["Use any OpenAI-compatible API over https.", "Paste its base URL and key here."],
  },
};

/** Human name for a provider id ("GEMINI" → "Google Gemini"); Tavily isn't in the LLM catalogue. */
export function providerName(id: Provider | undefined): string {
  if (!id) return "the provider";
  return id === "TAVILY" ? "Tavily" : PROVIDERS[id].name;
}

export type WizardError = { kind: "key" | "model" | "network" | "url" | "other"; message: string };

/**
 * Where the message is shown, which decides what "pick another model" means there: the wizard's model
 * list, the wizard's typed field (the provider gave no list), or Task routing (Settings has no picker).
 */
export type ErrorPlace = "wizard" | "wizard-typed" | "settings";

const PICK_MODEL: Record<ErrorPlace, string> = {
  wizard: "Choose one from the model list.",
  "wizard-typed": "Type a model name below.",
  settings: "Set a model for this key in Task routing.",
};

/**
 * Plain-language test errors (APPFLOW §4.3, DESIGN §12.2: say what happened + what to do, no blame).
 * Maps the API's machine-readable Problem `reason` (CONNECTION_TEST_FAILED) — never its wording.
 * `kind` drives the wizard: "key" clears the input, "model" offers the model picker.
 */
export function describeTestError(error: unknown, providerName: string, place: ErrorPlace = "wizard"): WizardError {
  const pickModel = PICK_MODEL[place];
  if (error instanceof ApiError) {
    const unreachable: WizardError = { kind: "network", message: `Couldn't reach ${providerName} right now. Try again in a minute.` };
    if (error.code === "UNSAFE_URL") {
      return { kind: "url", message: "This address can't be used. Use a public https URL." };
    }
    if (error.code === "VALIDATION_FAILED") {
      return { kind: "key", message: error.problem.detail ?? "Please check the key and try again." };
    }
    if (error.code === "RATE_LIMITED" && error.status === 429) {
      return { kind: "other", message: "Too many tests in the last hour. Try again later." };
    }
    switch (error.problem.reason) {
      case "KEY_INVALID":
        return { kind: "key", message: "This key didn't work. Check that you copied the whole key." };
      case "KEY_UNREADABLE":
        return { kind: "key", message: "The saved key can't be read anymore. Paste it again." };
      case "NO_CREDIT":
        return { kind: "key", message: `Your ${providerName} account has no credit left. Add credit or use a free provider.` };
      case "MODEL_NOT_FOUND":
        return { kind: "model", message: `Your key works, but this model isn't available. ${pickModel}` };
      case "NO_CHAT_MODEL":
        return { kind: "model", message: `Your key works, but we couldn't find a model it can use. ${pickModel}` };
      case "PROVIDER_ERROR":
        return { kind: "model", message: `${providerName} didn't accept the test request with this model. ${pickModel}` };
      case "PROVIDER_UNAVAILABLE":
        return unreachable;
    }
    if (error.status >= 500) return unreachable;
  }
  if (error instanceof TypeError) {
    return { kind: "network", message: "Couldn't reach Anveshan. Check your connection and try again." };
  }
  return { kind: "other", message: "Something didn't work. Try again in a minute." };
}
