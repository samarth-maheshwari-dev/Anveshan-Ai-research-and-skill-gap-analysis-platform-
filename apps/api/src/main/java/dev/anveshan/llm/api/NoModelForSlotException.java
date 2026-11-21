package dev.anveshan.llm.api;

/** No gateway endpoint is configured, so nothing can answer this slot (HTTP 422 {@code NO_MODEL_FOR_SLOT}). */
public class NoModelForSlotException extends RuntimeException {

	private final TaskSlot slot;

	public NoModelForSlotException(TaskSlot slot) {
		super("The AI service is not set up on this server (needed for " + slot + ").");
		this.slot = slot;
	}

	public TaskSlot slot() {
		return slot;
	}

}
