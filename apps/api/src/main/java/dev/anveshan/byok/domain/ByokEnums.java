package dev.anveshan.byok.domain;

/** Enum values match the CHECK constraints in {@code V202611080910__byok.sql}. */
public final class ByokEnums {

	private ByokEnums() {
	}

	public enum ConnectionKind {
		LLM, SEARCH
	}

	public enum AiProvider {
		GEMINI, GROQ, OPENROUTER, OPENAI, ANTHROPIC, CUSTOM, TAVILY
	}

	public enum ConnectionStatus {
		UNVERIFIED, VERIFIED, FAILING, DISABLED
	}

	public enum LlmCallStatus {
		OK, RATE_LIMITED, AUTH_ERROR, INVALID_OUTPUT, ERROR
	}

}
