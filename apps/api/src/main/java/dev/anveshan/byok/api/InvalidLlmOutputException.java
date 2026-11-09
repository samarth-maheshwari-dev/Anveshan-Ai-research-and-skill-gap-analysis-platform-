package dev.anveshan.byok.api;

/** The model gave no valid structured output in 3 attempts; the step fails and can be retried (E-FR-4). */
public class InvalidLlmOutputException extends RuntimeException {

	private final TaskSlot slot;

	public InvalidLlmOutputException(TaskSlot slot, String lastProblem) {
		super("The AI model returned invalid output 3 times for " + slot + " (" + lastProblem + ")");
		this.slot = slot;
	}

	public TaskSlot slot() {
		return slot;
	}

}
