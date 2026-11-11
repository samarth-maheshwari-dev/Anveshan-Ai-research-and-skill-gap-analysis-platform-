package dev.anveshan.research.app;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.anveshan.research.domain.ResearchEnums.StepKind;

/**
 * Does the work of one step kind (TECHSPEC §12.1). Called by {@link StepWorker} <b>outside</b> any DB
 * transaction; must be safe to run twice for the same step (upserts, {@code ON CONFLICT DO NOTHING}) because a
 * crashed step is re-run (AC-E1). Throw {@link StepFailedException} for a failure retrying can't fix; any other
 * exception is retried up to {@code anveshan.research.max-attempts}; {@code AllRoutesExhaustedException} pauses
 * the run.
 */
public interface StepHandler {

	StepKind kind();

	StepResult handle(StepContext step);

	/** What a handler gets: ids only plus the step's own input (no entities, TS-G15). */
	record StepContext(UUID runId, UUID ownerId, UUID stepId, StepKind kind, int stage, int seq, UUID subQuestionId,
			String inputJson, int attempt) {
	}

	/** The checkpoint saved with the step (never re-run once DONE) and the live events it produced. */
	record StepResult(Map<String, Object> output, List<NewEvent> events) {

		public StepResult {
			output = output == null ? Map.of() : Map.copyOf(output);
			events = events == null ? List.of() : List.copyOf(events);
		}

		public static StepResult of(Map<String, Object> output) {
			return new StepResult(output, List.of());
		}

	}

	/** One SSE event ({@code run_event} row), e.g. {@code source.found}; payload carries ids and counts only. */
	record NewEvent(String type, Map<String, Object> payload) {

		public NewEvent {
			payload = payload == null ? Map.of() : Map.copyOf(payload);
		}

	}

}
