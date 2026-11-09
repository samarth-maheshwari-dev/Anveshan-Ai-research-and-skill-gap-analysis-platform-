package dev.anveshan.byok.api;

/** No verified connection is routed to this slot (HTTP 422 {@code NO_MODEL_FOR_SLOT}, C-FR-3). */
public class NoModelForSlotException extends RuntimeException {

	private final TaskSlot slot;

	public NoModelForSlotException(TaskSlot slot) {
		super("No AI model is set up for " + slot + ". Add one in Settings → AI connections.");
		this.slot = slot;
	}

	public TaskSlot slot() {
		return slot;
	}

}
