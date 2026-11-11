package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.LlmResult;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.domain.PlanRules;
import dev.anveshan.research.domain.PlanRules.PlannedQuestion;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.domain.RunOptions.Objective;
import dev.anveshan.research.domain.RunOptions.OutputStyle;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.domain.RunOptions.TimeRange;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.research.infra.RunStore.RunRow;
import dev.anveshan.research.infra.TemplateStore;
import dev.anveshan.research.infra.TemplateStore.TemplateRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Planner (PRD §14.1, Module D): one PLAN call turns a run's topic and options into sub-questions with source
 * types and search queries, checked by code ({@link PlanRules}). Used by the PLAN step (stage 0) and by "Regenerate
 * plan" (D-FR-6). Saves nothing — the caller does.
 */
@Component
class Planner {

	static final String PROMPT = "planner";
	static final int PROMPT_VERSION = 1;

	/** What the model must return (validated by the gateway: schema + Bean Validation, ≤ 3 tries). */
	record PlanOutput(@NotNull @Size(min = 1, max = 12) List<@Valid PlanItem> subQuestions) {
	}

	record PlanItem(@NotBlank @Size(max = 600) String question, @Size(max = 5) List<String> sourceTypes,
			@Size(max = 8) List<String> queries) {
	}

	/** A checked plan and the model that wrote it. */
	record Proposal(List<PlannedQuestion> questions, String model) {
	}

	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final TemplateStore templates;
	private final ReportSections sections;
	private final JsonMapper json;

	Planner(LlmGateway gateway, PromptFiles prompts, TemplateStore templates, ReportSections sections, JsonMapper json) {
		this.gateway = gateway;
		this.prompts = prompts;
		this.templates = templates;
		this.sections = sections;
		this.json = json;
	}

	/**
	 * @param extraInstruction "Regenerate plan" guidance from the user (null for the first plan)
	 * @throws dev.anveshan.byok.api.NoModelForSlotException     no PLAN model
	 * @throws dev.anveshan.byok.api.ProviderUnavailableException the provider was busy or down
	 * @throws dev.anveshan.byok.api.AllRoutesExhaustedException  every route is rate-limited
	 * @throws dev.anveshan.byok.api.InvalidLlmOutputException    3 answers didn't match the schema
	 * @throws IllegalArgumentException                           the answer broke {@link PlanRules} (message for the user)
	 */
	Proposal propose(RunRow run, String extraInstruction, CallContext context) {
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		Optional<TemplateRow> template = Optional.ofNullable(config.templateCode()).flatMap(templates::byCode);
		List<String> sectionTitles = sections.titles(config);
		String instructions = config.instructions();
		if (extraInstruction != null) {
			instructions = (instructions == null ? "" : instructions + " | ") + "For this new plan: " + extraInstruction;
		}
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
		values.put("sections", sectionTitles.isEmpty() ? "a standard research report"
				: data(String.join(" | ", sectionTitles)));
		values.put("instructions", data(orNone(instructions)));
		values.put("includeSites", config.includeSites() == null ? "none" : String.join(", ", config.includeSites()));
		values.put("excludeSites", config.excludeSites() == null ? "none" : String.join(", ", config.excludeSites()));
		// a regenerated plan should differ, so a little more temperature
		Prompt prompt = prompts.load(PROMPT, PROMPT_VERSION, values, extraInstruction == null ? 0.3 : 0.6, 2048);

		LlmResult<PlanOutput> result = gateway.call(run.ownerId(), TaskSlot.PLAN, prompt, PlanOutput.class, context);
		return new Proposal(PlanRules.normalize(toPlanned(result.value()), config.sourceSet()), result.model());
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
					types.add(SourceType.valueOf(t.strip().toUpperCase(Locale.ROOT)));
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
