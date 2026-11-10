import { ApiError } from "@/lib/api/errors";
import type { ModelInfo, Provider } from "@/lib/api/hooks/connections";

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
    tagline: "Recommended · free tier",
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
    tagline: "Free tier · very fast",
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

const NOT_CHAT = /(embed|whisper|tts|audio|image|imagen|vision-only|guard|moderation|veo|transcribe|realtime|search)/i;

/** Per-provider taste: a fast, cheap, general chat model is the best default for every task. */
const PREFERRED: Partial<Record<Provider, RegExp[]>> = {
  GEMINI: [/^gemini-[\d.]+-flash$/, /^gemini-.*flash(?!.*(lite|thinking|exp|preview))/, /^gemini-.*flash/],
  GROQ: [/llama.*(versatile|instant)/, /llama/],
  OPENROUTER: [/:free$/],
  OPENAI: [/^gpt-[\d.o]+-mini$/, /mini/],
  ANTHROPIC: [/haiku/, /sonnet/],
};

/** The model the wizard tests with and fills into every slot. Undefined if the list has no chat model. */
export function preferredModel(provider: Provider, models: ModelInfo[]): string | undefined {
  const chat = models.map((m) => m.id ?? "").filter((id) => id && !NOT_CHAT.test(id));
  for (const pattern of PREFERRED[provider] ?? []) {
    const hit = chat.find((id) => pattern.test(id));
    if (hit) return hit;
  }
  return chat[0];
}

export type WizardError = { kind: "key" | "model" | "network" | "url" | "other"; message: string };

/** Plain-language wizard errors (APPFLOW §4.3, DESIGN §12.2: say what happened + what to do, no blame). */
export function describeTestError(error: unknown, providerName: string): WizardError {
  if (error instanceof ApiError) {
    const detail = (error.problem.detail ?? "").toLowerCase();
    if (error.code === "UNSAFE_URL") {
      return { kind: "url", message: "This address can't be used. Use a public https URL." };
    }
    if (error.code === "VALIDATION_FAILED") {
      return { kind: "key", message: error.problem.detail ?? "Please check the key and try again." };
    }
    if (detail.includes("key invalid") || detail.includes("no credit") || detail.includes("can't be read")) {
      return { kind: "key", message: "This key didn't work. Check that you copied the whole key." };
    }
    if (detail.includes("model not found") || detail.includes("no chat models")) {
      return { kind: "model", message: "Your key works, but this model isn't available. Pick another." };
    }
    if (detail.includes("reach") || detail.includes("unavailable") || detail.includes("http 5") || error.status >= 500) {
      return { kind: "network", message: `Couldn't reach ${providerName} right now. Try again in a minute.` };
    }
    if (error.code === "RATE_LIMITED" && error.status === 429) {
      return { kind: "other", message: "Too many tests in the last hour. Try again later." };
    }
  }
  if (error instanceof TypeError) {
    return { kind: "network", message: "Couldn't reach Anveshan. Check your connection and try again." };
  }
  return { kind: "other", message: "Something didn't work. Try again in a minute." };
}
