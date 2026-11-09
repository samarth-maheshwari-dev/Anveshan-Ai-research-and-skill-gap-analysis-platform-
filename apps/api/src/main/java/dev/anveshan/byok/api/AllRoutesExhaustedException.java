package dev.anveshan.byok.api;

import java.time.Instant;

/** Every route of a slot is rate-limited or cooling down; the caller should pause until {@code resumeAt}. */
public class AllRoutesExhaustedException extends RuntimeException {

	private final TaskSlot slot;
	private final Instant resumeAt;

	public AllRoutesExhaustedException(TaskSlot slot, Instant resumeAt) {
		super("All AI routes for " + slot + " are rate-limited until " + resumeAt);
		this.slot = slot;
		this.resumeAt = resumeAt;
	}

	public TaskSlot slot() {
		return slot;
	}

	public Instant resumeAt() {
		return resumeAt;
	}

}
