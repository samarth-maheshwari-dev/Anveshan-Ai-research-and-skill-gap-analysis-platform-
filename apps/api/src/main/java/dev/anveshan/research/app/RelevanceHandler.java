package dev.anveshan.research.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.ResearchSize;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.research.infra.SubQuestionStore.SubQuestionRow;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceLibrary.RelevantChunk;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 3: one RELEVANCE step per sub-question (TECHSPEC §12.1). Picks the passages most similar to the
 * sub-question from the sources this run uses — sources × chunks-per-source of them (PRD §14.4) — so EXTRACT
 * (P13) sends the LLM only those. Local embeddings, no LLM (token diet).
 */
@Component
class RelevanceHandler implements StepHandler {

	private final SourceLibrary library;
	private final RunStore runs;
	private final SubQuestionStore subQuestions;
	private final JsonMapper json;

	RelevanceHandler(SourceLibrary library, RunStore runs, SubQuestionStore subQuestions, JsonMapper json) {
		this.library = library;
		this.runs = runs;
		this.subQuestions = subQuestions;
		this.json = json;
	}

	@Override
	public StepKind kind() {
		return StepKind.RELEVANCE;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId())
				.orElseThrow(() -> new StepFailedException("This research no longer exists."));
		SubQuestionRow question = subQuestions.forRun(step.runId()).stream()
				.filter(q -> q.id().equals(step.subQuestionId())).findFirst()
				.orElseThrow(() -> new StepFailedException("This sub-question is no longer in the plan."));
		ResearchSize size = json.readValue(run.configJson(), RunConfig.class).resolved().size();
		int k = size.sourcesPerQuestion() * size.chunksPerSource();
		List<RelevantChunk> chunks = library.topChunks(step.runId(), question.text(), k);

		Map<String, Object> output = new LinkedHashMap<>();
		output.put("subQuestionId", question.id());
		output.put("chunks", chunks);
		String msg = chunks.isEmpty() ? "No passages to check for this sub-question."
				: "Picked the " + chunks.size() + " most relevant passages to check.";
		return new StepResult(output, List.of(new NewEvent("log",
				Map.of("stepId", step.stepId(), "level", chunks.isEmpty() ? "WARN" : "INFO", "message", msg))));
	}

}
