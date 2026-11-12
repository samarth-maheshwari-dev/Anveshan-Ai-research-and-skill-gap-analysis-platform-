package dev.anveshan.research.app;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.SubQuestionStore;

/**
 * The real stage graph (TECHSPEC §12.1), grown phase by phase. After the approved plan (stage 0) comes one SEARCH
 * step per sub-question (D-FR-5 "steps are materialized"). The SEARCH handler and the stages after it (FETCH …
 * ASSEMBLE) arrive in P11–P14; until a stage has a handler its steps fail with "isn't available yet".
 */
@Component
class ResearchStages implements StageMaterializer {

	private final SubQuestionStore subQuestions;

	ResearchStages(SubQuestionStore subQuestions) {
		this.subQuestions = subQuestions;
	}

	@Override
	public List<NewStep> next(RunSnapshot run, int closedStage) {
		if (closedStage == 0) {
			return subQuestions.forRun(run.id()).stream()
					.map(q -> new NewStep(StepKind.SEARCH, q.id(), Map.of("subQuestionId", q.id().toString())))
					.toList();
		}
		StepKind next = switch (closedStage) {
			case 1 -> StepKind.FETCH;       // read each sub-question's candidates (P12)
			case 2 -> StepKind.RELEVANCE;   // pick its most relevant passages (P12)
			case 3 -> StepKind.EXTRACT;     // claims from those passages — handler and real batching in P13
			default -> null;                // later stages arrive with their handlers (P13–P14)
		};
		if (next == null) {
			return List.of();
		}
		return subQuestions.forRun(run.id()).stream()
				.map(q -> new NewStep(next, q.id(), Map.of("subQuestionId", q.id().toString())))
				.toList();
	}

}
