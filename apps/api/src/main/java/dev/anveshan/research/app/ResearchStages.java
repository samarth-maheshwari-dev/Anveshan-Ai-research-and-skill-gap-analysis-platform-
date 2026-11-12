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
		if (closedStage == 1) { // after SEARCH: fetch each sub-question's candidates (FETCH handler arrives in P12)
			return subQuestions.forRun(run.id()).stream()
					.map(q -> new NewStep(StepKind.FETCH, q.id(), Map.of("subQuestionId", q.id().toString())))
					.toList();
		}
		return List.of(); // later stages arrive with their handlers (P12–P14)
	}

}
