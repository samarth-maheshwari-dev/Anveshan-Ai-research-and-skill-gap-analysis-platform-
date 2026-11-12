package dev.anveshan.research.app;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.research.infra.SubQuestionStore.SubQuestionRow;
import dev.anveshan.sources.api.SourceSearch;
import dev.anveshan.sources.api.SourceSearch.AdapterReport;
import dev.anveshan.sources.api.SourceSearch.SearchOutcome;
import dev.anveshan.sources.api.SourceSearch.SearchRequest;
import dev.anveshan.sources.api.SourceSearch.SourceKind;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 1: one SEARCH step per sub-question (TECHSPEC §12.1). Asks every fitting search API for candidates and
 * saves the ranked, de-duplicated list as the step's output (the checkpoint FETCH reads in P12). One API failing
 * is a {@code log} event, not a failure; every API failing is a retryable failure (T-11-05, T-11-06). No LLM.
 */
@Component
class SearchHandler implements StepHandler {

	/** Candidates kept per sub-question: twice the sources it needs (spares for pages that fail to load), ≤ 20. */
	static int keep(int sourcesPerQuestion) {
		return Math.min(2 * sourcesPerQuestion, 20);
	}

	private final SourceSearch search;
	private final RunStore runs;
	private final SubQuestionStore subQuestions;
	private final JsonMapper json;
	private final Clock clock;

	SearchHandler(SourceSearch search, RunStore runs, SubQuestionStore subQuestions, JsonMapper json, Clock clock) {
		this.search = search;
		this.runs = runs;
		this.subQuestions = subQuestions;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public StepKind kind() {
		return StepKind.SEARCH;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId())
				.orElseThrow(() -> new StepFailedException("This research no longer exists."));
		SubQuestionRow question = subQuestions.forRun(step.runId()).stream()
				.filter(q -> q.id().equals(step.subQuestionId())).findFirst()
				.orElseThrow(() -> new StepFailedException("This sub-question is no longer in the plan."));
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();

		Set<SourceKind> kinds = EnumSet.noneOf(SourceKind.class);
		List<SourceType> types = question.sourceTypes().isEmpty() ? config.sources() : question.sourceTypes();
		types.forEach(t -> kinds.add(SourceKind.valueOf(t.name())));

		SearchOutcome outcome = search.search(new SearchRequest(step.ownerId(), question.text(), question.queries(),
				kinds, since(config), config.includeSites(), config.excludeSites(),
				keep(config.size().sourcesPerQuestion())));

		if (outcome.adapters().isEmpty()) {
			throw new StepFailedException("No search service fits this sub-question's sources ("
					+ String.join(", ", types.stream().map(Enum::name).toList()) + "). Edit the plan and start again.");
		}
		if (outcome.allFailed()) {
			throw new StepFailedException("None of the search services answered. Anveshan will try again shortly.", true);
		}

		List<NewEvent> events = new ArrayList<>();
		for (AdapterReport a : outcome.adapters()) {
			String msg = switch (a.status()) {
				case OK -> null;
				case FAILED -> a.label() + " didn't answer — continuing with the other sources.";
				case SKIPPED_BUDGET -> a.label() + " reached today's free limit — skipped until tomorrow.";
				case SKIPPED_CIRCUIT_OPEN -> a.label() + " has been failing — skipped for a minute.";
			};
			if (msg != null) {
				events.add(log("WARN", step, msg));
			}
		}
		events.add(log("INFO", step, "Found " + outcome.candidates().size() + " candidate sources for \""
				+ abbreviate(question.text()) + "\"."));

		Map<String, Object> output = new LinkedHashMap<>();
		output.put("subQuestionId", question.id());
		output.put("candidates", outcome.candidates());
		output.put("adapters", outcome.adapters());
		return new StepResult(output, events);
	}

	/** The run's time range as a start day (TimeRange → date, from today in UTC). */
	LocalDate since(RunConfig config) {
		LocalDate today = LocalDate.now(clock);
		return switch (config.timeRange()) {
			case ANY -> null;
			case LAST_1_YEAR -> today.minusYears(1);
			case LAST_2_YEARS -> today.minusYears(2);
			case LAST_5_YEARS -> today.minusYears(5);
			case SINCE_YEAR -> config.sinceYear() == null ? null : LocalDate.of(config.sinceYear(), 1, 1);
		};
	}

	private static NewEvent log(String level, StepContext step, String message) {
		return new NewEvent("log", Map.of("stepId", step.stepId(), "level", level, "message", message));
	}

	private static String abbreviate(String s) {
		return s.length() <= 80 ? s : s.substring(0, 79) + "…";
	}

}
