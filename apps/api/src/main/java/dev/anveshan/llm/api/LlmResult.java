package dev.anveshan.llm.api;

/** A validated LLM answer plus which gateway endpoint and model produced it and what it cost. */
public record LlmResult<T>(T value, String endpoint, String model, Integer tokensIn, Integer tokensOut, int attempts) {
}
