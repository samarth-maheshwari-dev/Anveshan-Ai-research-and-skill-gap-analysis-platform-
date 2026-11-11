package dev.anveshan.research.domain;

/** Values of the research tables' {@code CHECK} constraints (TECHSPEC §7.5); names match the SQL exactly. */
public final class ResearchEnums {

	private ResearchEnums() {
	}

	/** {@code research_run.status} — moves only through {@link RunStateMachine} (L5). */
	public enum RunStatus {
		PLANNING, AWAITING_APPROVAL, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
	}

	/** {@code research_run.pause_reason}. */
	public enum PauseReason {
		USER, AI_LIMIT_REACHED, SEARCH_LIMIT_REACHED
	}

	/** {@code research_step.status}. A DONE step is never run again (L4). */
	public enum StepStatus {
		PENDING, RUNNING, DONE, FAILED, SKIPPED
	}

	/** {@code research_step.kind} — the stages of TECHSPEC §12.1. */
	public enum StepKind {
		PLAN, SEARCH, FETCH, RELEVANCE, EXTRACT, VERIFY, LABEL, WRITE_SECTION, ASSEMBLE
	}

}
