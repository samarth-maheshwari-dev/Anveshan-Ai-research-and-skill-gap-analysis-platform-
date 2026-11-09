package dev.anveshan.byok.api;

import java.util.UUID;

/** A validated LLM answer plus where it came from and what it cost. */
public record LlmResult<T>(T value, UUID connectionId, String model, Integer tokensIn, Integer tokensOut,
		int attempts) {
}
