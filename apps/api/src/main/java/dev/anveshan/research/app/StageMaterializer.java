package dev.anveshan.research.app;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.anveshan.research.domain.ResearchEnums.StepKind;

/**
 * Decides the next stage when one closes (TECHSPEC §12.1: later stages are materialized dynamically, e.g. the
 * number of EXTRACT batches depends on what RELEVANCE found). Runs inside the engine's short transaction that holds
 * the run's row lock — read the DB if needed, but never call the network here.
 */
public interface StageMaterializer {

	/** Steps of stage {@code closedStage + 1}; an empty list means the run is finished (→ COMPLETED). */
	List<NewStep> next(RunSnapshot run, int closedStage);

	record RunSnapshot(UUID id, UUID ownerId, String topic, String configJson) {
	}

	record NewStep(StepKind kind, UUID subQuestionId, Map<String, Object> input) {

		public NewStep {
			input = input == null ? Map.of() : Map.copyOf(input);
		}

	}

}
