package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.LlmResult;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.domain.PlanRules;
import dev.anveshan.research.domain.PlanRules.PlannedQuestion;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.domain.RunOptions.Objective;
import dev.anveshan.research.domain.RunOptions.OutputStyle;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.domain.RunOptions.TimeRange;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.research.infra.TemplateStore;
import dev.anveshan.research.infra.TemplateStore.TemplateRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 0: the Planner (PRD §14.1, Module D). One PLAN call turns the topic into sub-questions with source types
 * and search queries; the result is checked by code ({@link PlanRules}) and saved as the run's plan. Safe to run
 * twice: the plan is replaced as a whole. Closing stage 0 moves the run to AWAITING_APPROVAL (RunEngine).
 */
@Component
class PlanHandler implements StepHandler {

	static final String PROMPT = "planner";
	static final int PROMPT_VERSION = 1;

	/** What the model must return (validated by the gateway: schema + Bean Validation, ≤ 3 tries). */
	record PlanOutput(@NotNull @Size(min = 1, max = 12) List<@Valid PlanItem> subQuestions) {
	}

	record PlanItem(@NotBlank @Size(max = 600) String question, @Size(max = 5) List<String> sourceTypes,
			@Size(max = 8) List<String> queries) {
	}

	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final RunStore runs;
	private final TemplateStore templates;
	private final SubQuestionStore subQuestions;
	private final TransactionTemplate tx;
	private final JsonMapper json;
	private final ReportSections sections;

	PlanHandler(LlmGateway gateway, PromptFiles prompts, RunStore runs, TemplateStore templates,
			SubQuestionStore subQuestions, TransactionTemplate researchTx, JsonMapper json, ReportSections sections) {
		this.gateway = gateway;
		this.prompts = prompts;
		this.runs = runs;
		this.templates = templates;
		this.subQuestions = subQuestions;
		this.tx = researchTx;
		this.json = json;
		this.sections = sections;
	}

	@Override
	public StepKind kind() {
		return StepKind.PLAN;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId()).orElseThrow(() -> new StepFailedException("This research no longer exists."));
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		Optional<TemplateRow> template = Optional.ofNullable(config.templateCode()).flatMap(templates::byCode);
		List<String> sectionTitles = sections.titles(config);
		Map<String, String> values = new HashMap<>();
		values.put("count", Integer.toString(config.size().subQuestions()));
		values.put("sourceTypes", String.join(", ", config.sources().stream().map(Enum::name).toList()));
		values.put("topic", data(run.topic()));
		values.put("objective", config.objective() == Objective.OTHER ? "the user's own (see <objective>)"
				: config.objective().name());
		values.put("objectiveText", data(orNone(config.objectiveText())));
		values.put("depth", config.depth().name());
		values.put("timeRange", config.timeRange() == TimeRange.SINCE_YEAR ? "since " + config.sinceYear()
				: config.timeRange().name());
		values.put("language", config.language() == Language.hi ? "Hindi" : "English");
		values.put("outputStyle", config.outputStyle() == OutputStyle.CUSTOM ? "the user's own (see <format>)"
				: config.outputStyle().name());
		values.put("outputStyleText", data(orNone(config.outputStyleText())));
		values.put("template", template.map(TemplateRow::name).orElse("none"));
		values.put("sections", sectionTitles.isEmpty() ? "a standard research report" : data(String.join(" | ", sectionTitles)));
		values.put("instructions", data(orNone(config.instructions())));
		values.put("includeSites", config.includeSites() == null ? "none" : String.join(", ", config.includeSites()));
		values.put("excludeSites", config.excludeSites() == null ? "none" : String.join(", ", config.excludeSites()));
		Prompt prompt = prompts.load(PROMPT, PROMPT_VERSION, values, 0.3, 2048);

		LlmResult<PlanOutput> result;
		try {
			result = gateway.call(step.ownerId(), TaskSlot.PLAN, prompt, PlanOutput.class);
		}
		catch (NoModelForSlotException e) {
			throw new StepFailedException("No AI model is set for planning. Choose one in Settings → AI, then start again.");
		}
		catch (InvalidLlmOutputException e) {
			throw new StepFailedException(
					"The AI didn't return a usable plan. Start the research again, or pick another model for planning.");
		}

		List<PlannedQuestion> plan;
		try {
			plan = PlanRules.normalize(toPlanned(result.value()), config.sourceSet());
		}
		catch (IllegalArgumentException e) {
			throw new StepFailedException("The AI's plan wasn't usable (" + e.getMessage()
					+ ") Start the research again, or pick another model for planning.");
		}
		tx.executeWithoutResult(s -> subQuestions.replace(step.runId(), plan, null));
		return new StepResult(Map.of("subQuestions", plan.size(), "model", result.model()),
				List.of(new NewEvent("plan.ready", Map.of("subQuestions", plan.size()))));
	}

	/** User text can't open or close the prompt's tags (RULES §10): angle brackets become look-alikes. */
	static String data(String s) {
		return s.replace('<', '‹').replace('>', '›');
	}

	private static String orNone(String s) {
		return s == null ? "none" : s;
	}

	/** Keeps at most 8 questions; unknown source types are dropped (the model may only pick from the list). */
	private static List<PlannedQuestion> toPlanned(PlanOutput out) {
		List<PlannedQuestion> list = new ArrayList<>();
		for (PlanItem item : out.subQuestions()) {
			if (list.size() == PlanRules.MAX_SUB_QUESTIONS) {
				break;
			}
			List<SourceType> types = new ArrayList<>();
			for (String t : item.sourceTypes() == null ? List.<String>of() : item.sourceTypes()) {
				try {
					types.add(SourceType.valueOf(t.strip().toUpperCase(java.util.Locale.ROOT)));
				}
				catch (IllegalArgumentException ignored) {
					// a source type we don't support (e.g. YOUTUBE): leave it out
				}
			}
			list.add(new PlannedQuestion(item.question(), types, item.queries()));
		}
		return list;
	}

}
