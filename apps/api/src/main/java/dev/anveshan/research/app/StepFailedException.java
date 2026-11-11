package dev.anveshan.research.app;

/**
 * A step failure with a message for the user (plain language, never secrets). By default retrying won't fix it
 * (e.g. a 4xx that isn't a 429): the step is FAILED at once and the run FAILED. A {@link #retryable} failure (the
 * provider was busy or down) is tried again with backoff, and only the last attempt fails the run.
 */
public class StepFailedException extends RuntimeException {

	private final boolean retryable;

	public StepFailedException(String safeMessage) {
		this(safeMessage, false);
	}

	public StepFailedException(String safeMessage, boolean retryable) {
		super(safeMessage);
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}

}
