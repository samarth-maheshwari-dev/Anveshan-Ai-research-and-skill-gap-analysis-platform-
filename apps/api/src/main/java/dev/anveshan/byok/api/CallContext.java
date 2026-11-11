package dev.anveshan.byok.api;

import java.util.UUID;

/**
 * What an LLM call is for, so the usage log ({@code llm_call.run_id / step_id}) can show cost per research run.
 * Ids only — never prompt text.
 */
public record CallContext(UUID runId, UUID stepId) {

	/** A call outside a research run (connection test, Ask, …). */
	public static final CallContext NONE = new CallContext(null, null);

	public static CallContext step(UUID runId, UUID stepId) {
		return new CallContext(runId, stepId);
	}

}
