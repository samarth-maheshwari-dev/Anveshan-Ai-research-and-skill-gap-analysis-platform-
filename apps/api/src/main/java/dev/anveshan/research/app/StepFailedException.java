package dev.anveshan.research.app;

/**
 * A step failure that retrying won't fix (e.g. a 4xx that isn't a 429): the step is FAILED at once and the run
 * FAILED. The message is shown to the user, so it must be plain language and never contain secrets.
 */
public class StepFailedException extends RuntimeException {

	public StepFailedException(String safeMessage) {
		super(safeMessage);
	}

}
