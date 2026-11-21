package dev.anveshan.llm.api;

/**
 * Which job an LLM call does; each slot can use its own model per gateway endpoint.
 * Fixed set (RULES §8) — values match the {@code slot} CHECK constraints.
 */
public enum TaskSlot {
	PLAN, EXTRACT, VERIFY, WRITE, ASK, INTERVIEW
}
