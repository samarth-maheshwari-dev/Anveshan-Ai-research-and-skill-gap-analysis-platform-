package dev.anveshan.research.domain;

import dev.anveshan.research.domain.ResearchEnums.RunStatus;

/** A run status change the state machine doesn't allow (L5) → {@code 409 RUN_INVALID_STATE}. */
public class RunInvalidStateException extends RuntimeException {

	private final RunStatus from;
	private final RunStatus to;

	public RunInvalidStateException(RunStatus from, RunStatus to) {
		super("A run that is " + from + " can't move to " + to + ".");
		this.from = from;
		this.to = to;
	}

	public String code() {
		return "RUN_INVALID_STATE";
	}

	public RunStatus from() {
		return from;
	}

	public RunStatus to() {
		return to;
	}

}
