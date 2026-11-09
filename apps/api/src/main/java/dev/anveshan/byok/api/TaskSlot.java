package dev.anveshan.byok.api;

/**
 * Which job an LLM call does; each slot is routed to a user-chosen connection + model.
 * Fixed set (RULES §8) — values match the {@code slot} CHECK constraints.
 */
public enum TaskSlot {
	PLAN, EXTRACT, VERIFY, WRITE, ASK, INTERVIEW
}
