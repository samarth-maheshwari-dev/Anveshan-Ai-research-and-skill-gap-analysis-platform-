package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.ProviderUnavailableException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.report.api.Reports;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.domain.RunOptions.Objective;
import dev.anveshan.research.domain.RunOptions.OutputStyle;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.ReportClaim;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.sources.api.SourceLibrary.SourceInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 7: one WRITE_SECTION step per report section (TECHSPEC §12.9). The model gets only this section's verified
 * claims (short ids C1, C2 …) and must end every factual sentence with their markers; code keeps only markers of
 * claims it was given (T-14-01) and counts unmarked factual sentences as a quality metric.
 */
@Component
class WriteHandler implements StepHandler {

	static final String PROMPT = "writer";
	static final int PROMPT_VERSION = 1;

	record WriteOutput(@NotBlank @Size(max = 30_000) String markdown) {
	}

	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final ClaimLibrary claims;
	private final SourceLibrary sources;
	private final RunStore runs;
	private final ReportSections sections;
	private final JsonMapper json;
	private final Reports reports;
	private final Counter unmarked;
	private final Counter removed;

	WriteHandler(LlmGateway gateway, PromptFiles prompts, ClaimLibrary claims, SourceLibrary sources, RunStore runs,
			ReportSections sections, JsonMapper json, MeterRegistry meters, Reports reports) {
		this.gateway = gateway;
		this.prompts = prompts;
		this.claims = claims;
		this.sources = sources;
		this.runs = runs;
		this.sections = sections;
		this.json = json;
		this.reports = reports;
		this.unmarked = Counter.builder("report.unmarked_sentences")
				.description("Factual-looking sentences the writer left without a citation").register(meters);
		this.removed = Counter.builder("report.removed_markers")
				.description("Citation markers that pointed at no claim given to the writer").register(meters);
	}

	@Override
	public StepKind kind() {
		return StepKind.WRITE_SECTION;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId())
				.orElseThrow(() -> new StepFailedException("This research no longer exists."));
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		boolean hindi = config.language() == Language.hi;
		JsonNode in = json.readTree(step.inputJson());
		String key = in.path("key").asString("section");
		String title = in.path("title").asString("Section");
		List<UUID> ids = new ArrayList<>();
		in.path("claimIds").forEach(id -> ids.add(UUID.fromString(id.asString())));

		List<ReportClaim> usable = claims.reportClaims(step.runId(), ids).stream()
				.filter(c -> "SUPPORTED".equals(c.label()) || "WEAK".equals(c.label())).toList();
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("position", in.path("position").asInt(0));
		output.put("key", key);
		output.put("title", title);
		if (usable.isEmpty()) {
			output.put("markdown", hindi ? "इस हिस्से के लिए पर्याप्त सत्यापित प्रमाण नहीं मिला।"
					: "Not enough verified evidence was found for this section.");
			output.put("claims", 0);
			return new StepResult(output, List.of(log("WARN", step, "\"" + title + "\": no verified claims to write from.")));
		}

		Map<String, UUID> aliases = new LinkedHashMap<>();
		Map<UUID, SourceInfo> sourceOf = new HashMap<>();
		sources.describe(usable.stream().map(ReportClaim::sourceId).distinct().toList())
				.forEach(s -> sourceOf.put(s.id(), s));
		StringBuilder claimList = new StringBuilder();
		for (ReportClaim c : usable) {
			String alias = "C" + (aliases.size() + 1);
			aliases.put(alias, c.id());
			SourceInfo s = sourceOf.get(c.sourceId());
			claimList.append(alias).append(" [").append(c.label()).append("] (")
					.append(s == null ? "source" : Planner.data(s.title()))
					.append(s != null && s.publishedAt() != null ? ", " + s.publishedAt().getYear() : "").append("): ")
					.append(Planner.data(c.text())).append('\n');
		}
		List<String> others = new ArrayList<>();
		in.path("allSections").forEach(t -> {
			if (!t.asString().equals(title)) {
				others.add(t.asString());
			}
		});
		Map<String, String> values = new HashMap<>();
		values.put("language", hindi ? "Hindi" : "English");
		values.put("topic", Planner.data(run.topic()));
		values.put("objective", config.objective() == Objective.OTHER ? Planner.data(String.valueOf(config.objectiveText()))
				: config.objective().name());
		values.put("format", config.outputStyle() == OutputStyle.CUSTOM ? Planner.data(String.valueOf(config.outputStyleText()))
				: config.outputStyle().name());
		values.put("templateGuidance", Planner.data(sections.writerInstructions(config)));
		values.put("userInstructions", Planner.data(config.instructions() == null ? "" : config.instructions()));
		values.put("sectionTitle", Planner.data(title));
		values.put("sectionPurpose", Planner.data(in.path("instructions").asString("")));
		values.put("otherSections", Planner.data(others.isEmpty() ? "none" : String.join(", ", others)));
		values.put("claims", claimList.toString().strip());
		Prompt prompt = prompts.load(PROMPT, PROMPT_VERSION, values, 0.4, 2500);

		String markdown;
		List<NewEvent> events = new ArrayList<>();
		try {
			markdown = gateway.call(step.ownerId(), TaskSlot.WRITE, prompt, WriteOutput.class,
					CallContext.step(step.runId(), step.stepId())).value().markdown();
		}
		catch (NoModelForSlotException e) {
			throw new StepFailedException("No AI model is set for writing. Choose one for \"Write\" in Settings → AI, "
					+ "then retry this step.");
		}
		catch (ProviderUnavailableException e) {
			throw new StepFailedException("Your AI provider didn't answer — it was busy or down. Anveshan will try again.",
					true);
		}
		catch (InvalidLlmOutputException e) {
			markdown = hindi ? "यह हिस्सा लिखा नहीं जा सका। रिपोर्ट स्टूडियो में इसे दोबारा लिखवाएँ।"
					: "This section couldn't be written. You can regenerate it in the Report Studio.";
			events.add(log("WARN", step, "\"" + title + "\" couldn't be written: the AI's answer wasn't usable."));
		}
		Reports.MarkerCheck r = reports.checkMarkers(markdown.replaceAll("(?m)^#\\s+.*$", "").strip(), aliases);
		removed.increment(r.removed());
		unmarked.increment(r.unmarked());
		output.put("markdown", r.markdown());
		output.put("claims", usable.size());
		output.put("removedMarkers", r.removed());
		output.put("unmarkedSentences", r.unmarked());
		events.add(log("INFO", step, "Wrote \"" + title + "\" from " + usable.size() + " claims."));
		if (r.removed() > 0) {
			events.add(log("WARN", step, "Removed " + r.removed() + " citation markers that pointed at unknown claims."));
		}
		return new StepResult(output, events);
	}

	private static NewEvent log(String level, StepContext step, String message) {
		return new NewEvent("log", Map.of("stepId", step.stepId(), "level", level, "message", message));
	}

}
