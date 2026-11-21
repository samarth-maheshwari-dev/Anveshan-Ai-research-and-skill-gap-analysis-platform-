package dev.anveshan.llm.domain;

/** Enum values match the CHECK constraints on {@code llm_call}. */
public final class LlmEnums {

	private LlmEnums() {
	}

	public enum LlmCallStatus {
		OK, RATE_LIMITED, AUTH_ERROR, INVALID_OUTPUT, ERROR
	}

}
