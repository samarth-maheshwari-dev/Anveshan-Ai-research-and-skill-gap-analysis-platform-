package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceLibrary.IngestResult;
import dev.anveshan.sources.api.SourceLibrary.Outcome;
import dev.anveshan.sources.api.SourceSearch.Candidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 2: one FETCH step per sub-question (TECHSPEC §12.1, §12.4). Reads the SEARCH checkpoint's ranked
 * candidates best-first until the run has enough readable sources for this sub-question (the depth's sources per
 * question), and links them to the run. Pages that can't be opened are reported, never fatal. No LLM.
 */
@Component
class FetchHandler implements StepHandler {

	private final SourceLibrary library;
	private final RunStore runs;
	private final StepStore steps;
	private final JsonMapper json;

	FetchHandler(SourceLibrary library, RunStore runs, StepStore steps, JsonMapper json) {
		this.library = library;
		this.runs = runs;
		this.steps = steps;
		this.json = json;
	}

	@Override
	public StepKind kind() {
		return StepKind.FETCH;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId())
				.orElseThrow(() -> new StepFailedException("This research no longer exists."));
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		String searchOutput = steps.doneOutput(step.runId(), StepKind.SEARCH, step.subQuestionId())
				.orElseThrow(() -> new StepFailedException("The search for this sub-question has no results to read."));
		List<Candidate> candidates = new ArrayList<>();
		for (JsonNode c : json.readTree(searchOutput).path("candidates")) {
			candidates.add(json.treeToValue(c, Candidate.class));
		}

		int needed = config.size().sourcesPerQuestion();
		List<IngestResult> results = library.ingestBest(candidates, needed);

		List<Map<String, Object>> used = new ArrayList<>();
		List<Map<String, Object>> unavailable = new ArrayList<>();
		int fresh = 0;
		int reused = 0;
		for (IngestResult r : results) {
			if (r.outcome() == Outcome.UNAVAILABLE) {
				Map<String, Object> u = new LinkedHashMap<>();
				u.put("url", r.url());
				u.put("title", r.title());
				u.put("reason", r.reason());
				unavailable.add(u);
				continue;
			}
			if (used.size() >= needed) {
				break;
			}
			library.useInRun(step.ownerId(), step.runId(), r.sourceId(), step.subQuestionId(), used.size() + 1);
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("sourceId", r.sourceId());
			s.put("title", r.title());
			s.put("url", r.url());
			s.put("tier", r.tier());
			s.put("tierReason", r.tierReason());
			s.put("outcome", r.outcome().name());
			s.put("chunks", r.chunks());
			used.add(s);
			if (r.outcome() == Outcome.NEW) {
				fresh++;
			}
			else {
				reused++;
			}
		}

		List<NewEvent> events = new ArrayList<>();
		if (used.isEmpty()) {
			events.add(log("WARN", step, "No readable source was found for this sub-question."));
		}
		else {
			events.add(log("INFO", step, "Read " + used.size() + " sources (" + fresh + " new, " + reused
					+ " already known)."));
		}
		if (!unavailable.isEmpty()) {
			events.add(log("WARN", step, unavailable.size() + (unavailable.size() == 1 ? " page" : " pages")
					+ " couldn't be opened — " + unavailable.get(0).get("reason")));
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("subQuestionId", step.subQuestionId());
		output.put("sources", used);
		output.put("unavailable", unavailable);
		return new StepResult(output, events);
	}

	private static NewEvent log(String level, StepContext step, String message) {
		return new NewEvent("log", Map.of("stepId", step.stepId(), "level", level, "message", message));
	}

}
