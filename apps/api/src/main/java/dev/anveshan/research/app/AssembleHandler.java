package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.ProviderUnavailableException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.report.api.Reports;
import dev.anveshan.report.api.Reports.Assembled;
import dev.anveshan.report.api.Reports.ConflictDraft;
import dev.anveshan.report.api.Reports.SectionDraft;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.ResearchEnums.StepStatus;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.Conflict;
import dev.anveshan.sources.api.SourceLibrary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 8: ASSEMBLE (TECHSPEC §12.9). Joins the written sections, builds the Conflicts section by code from the
 * CONFLICTING claims (the WRITE slot adds only a one-line possible reason per conflict, in one call), numbers the
 * citations and the References list, and saves the report. The run then completes.
 */
@Component
class AssembleHandler implements StepHandler {

	static final int MAX_CONFLICTS = 20;
	/** NO_SOURCE claims listed as open questions (by code, no LLM). */
	static final int MAX_OPEN_QUESTIONS = 15;

	record ReasonsOutput(@NotNull @Size(max = 40) List<@Valid Reason> reasons) {
	}

	record Reason(@NotBlank @Size(max = 10) String id, @NotBlank @Size(max = 600) String reason) {
	}

	private final Reports reports;
	private final ClaimLibrary claims;
	private final SourceLibrary sources;
	private final StepStore steps;
	private final RunStore runs;
	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final JsonMapper json;
	private final WriteHandler writer;

	AssembleHandler(Reports reports, ClaimLibrary claims, SourceLibrary sources, StepStore steps, RunStore runs,
			LlmGateway gateway, PromptFiles prompts, JsonMapper json, WriteHandler writer) {
		this.writer = writer;
		this.reports = reports;
		this.claims = claims;
		this.sources = sources;
		this.steps = steps;
		this.runs = runs;
		this.gateway = gateway;
		this.prompts = prompts;
		this.json = json;
	}

	@Override
	public StepKind kind() {
		return StepKind.ASSEMBLE;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId())
				.orElseThrow(() -> new StepFailedException("This research no longer exists."));
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		boolean hindi = config.language() == Language.hi;

		List<NewEvent> rewriteLogs = new ArrayList<>();
		List<JsonNode> written = writtenSections(step, run, rewriteLogs);
		written.sort(Comparator.comparingInt(n -> n.path("position").asInt()));
		List<SectionDraft> drafts = written.stream().map(n -> new SectionDraft(n.path("key").asString(),
				n.path("title").asString(), n.path("markdown").asString(""))).toList();

		List<Conflict> conflicts = claims.conflicts(step.runId());
		if (conflicts.size() > MAX_CONFLICTS) {
			conflicts = conflicts.subList(0, MAX_CONFLICTS);
		}
		Map<String, String> reasons = reasons(step, conflicts, hindi);
		List<ConflictDraft> conflictDrafts = new ArrayList<>();
		for (int i = 0; i < conflicts.size(); i++) {
			Conflict c = conflicts.get(i);
			conflictDrafts.add(new ConflictDraft(c.claimId(), c.text(), c.supportingSources(), c.contradictingSources(),
					reasons.get("K" + (i + 1))));
		}

		List<Reports.OpenQuestionDraft> open = claims.openQuestions(step.runId(), MAX_OPEN_QUESTIONS).stream()
				.map(q -> new Reports.OpenQuestionDraft(q.claimId(), q.text())).toList();

		Assembled a = reports.assemble(new Reports.AssembleInput(step.runId(), step.ownerId(), run.topic(),
				config.language().name(), drafts, conflictDrafts, open));

		Map<String, Object> output = new LinkedHashMap<>();
		output.put("reportId", a.reportId());
		output.put("sections", a.sections());
		output.put("citations", a.citations());
		output.put("sources", a.sources());
		output.put("conflicts", conflicts.size());
		output.put("openQuestions", open.size());
		List<NewEvent> events = new ArrayList<>(rewriteLogs);
		events.addAll(List.of(
				new NewEvent("log", Map.of("stepId", step.stepId(), "level", "INFO", "message",
						"Report ready: " + drafts.size() + " sections, " + a.citations() + " citations from "
								+ a.sources() + " sources.")),
				new NewEvent("report.ready", Map.of("reportId", a.reportId()))));
		return new StepResult(output, events);
	}

	/**
	 * The DONE section checkpoints. A section written from a claim whose source was skipped after it was written
	 * (E-FR-5: pause → skip → resume) is written again from the same step input — the writer keeps only claims of
	 * sources still used — so the skipped source's statements don't stay in the text uncited. The DONE step itself
	 * is never re-run (L4).
	 */
	private List<JsonNode> writtenSections(StepContext step, RunStore.RunRow run, List<NewEvent> logs) {
		List<StepStore.StepRow> done = steps.forRun(step.runId()).stream()
				.filter(s -> s.kind() == StepKind.WRITE_SECTION && s.status() == StepStatus.DONE).toList();
		Set<UUID> used = new HashSet<>();
		sources.runSources(step.runId()).stream().filter(s -> "USED".equals(s.status()))
				.forEach(s -> used.add(s.sourceId()));
		List<UUID> allClaims = new ArrayList<>();
		Map<UUID, JsonNode> inputs = new HashMap<>();
		for (StepStore.StepRow s : done) {
			JsonNode in = json.readTree(s.inputJson());
			inputs.put(s.id(), in);
			in.path("claimIds").forEach(id -> allClaims.add(UUID.fromString(id.asString())));
		}
		Map<UUID, UUID> sourceOf = new HashMap<>();
		claims.reportClaims(step.runId(), allClaims.stream().distinct().toList())
				.forEach(c -> sourceOf.put(c.id(), c.sourceId()));

		List<JsonNode> out = new ArrayList<>();
		for (StepStore.StepRow s : done) {
			JsonNode in = inputs.get(s.id());
			JsonNode section = json.readTree(s.outputJson());
			boolean stale = false;
			for (JsonNode id : in.path("claimIds")) {
				UUID src = sourceOf.get(UUID.fromString(id.asString()));
				stale |= src != null && !used.contains(src);
			}
			if (stale) {
				String markdown = rewrite(step, run, in);
				var copy = (tools.jackson.databind.node.ObjectNode) section.deepCopy();
				copy.put("markdown", markdown);
				section = copy;
				logs.add(new NewEvent("log", Map.of("stepId", step.stepId(), "level", "INFO", "message",
						"Wrote \"" + in.path("title").asString("Section") + "\" again without the skipped source.")));
			}
			out.add(section);
		}
		return out;
	}

	private String rewrite(StepContext step, RunStore.RunRow run, JsonNode in) {
		boolean hindi = json.readValue(run.configJson(), RunConfig.class).resolved().language() == Language.hi;
		try {
			WriteHandler.Written w = writer.write(step.ownerId(), run, in, CallContext.step(step.runId(), step.stepId()));
			if (w.markdown() != null) {
				return w.markdown();
			}
			return hindi ? "इस हिस्से के लिए पर्याप्त सत्यापित प्रमाण नहीं मिला।"
					: "Not enough verified evidence was found for this section.";
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
			return hindi ? "यह हिस्सा लिखा नहीं जा सका। रिपोर्ट स्टूडियो में इसे दोबारा लिखवाएँ।"
					: "This section couldn't be written. You can regenerate it in the Report Studio.";
		}
	}

	/** One WRITE call for every conflict's possible reason; any failure just leaves the reasons out. */
	private Map<String, String> reasons(StepContext step, List<Conflict> conflicts, boolean hindi) {
		if (conflicts.isEmpty()) {
			return Map.of();
		}
		Map<java.util.UUID, String> titles = new HashMap<>();
		List<java.util.UUID> ids = new ArrayList<>();
		conflicts.forEach(c -> {
			ids.addAll(c.supportingSources());
			ids.addAll(c.contradictingSources());
		});
		sources.describe(ids.stream().distinct().toList()).forEach(s -> titles.put(s.id(), s.title()));
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < conflicts.size(); i++) {
			Conflict c = conflicts.get(i);
			list.append('K').append(i + 1).append(": ").append(Planner.data(c.text())).append("\n   supported by: ")
					.append(Planner.data(String.join("; ", c.supportingSources().stream().map(id -> titles.getOrDefault(id, "a source")).toList())))
					.append("\n   contradicted by: ")
					.append(Planner.data(String.join("; ", c.contradictingSources().stream().map(id -> titles.getOrDefault(id, "a source")).toList())))
					.append('\n');
		}
		try {
			ReasonsOutput out = gateway.call(step.ownerId(), TaskSlot.WRITE,
					prompts.load("conflicts", 1, Map.of("language", hindi ? "Hindi" : "English", "conflicts",
							list.toString().strip()), 0.2, 1500),
					ReasonsOutput.class, CallContext.step(step.runId(), step.stepId())).value();
			Map<String, String> byId = new HashMap<>();
			for (Reason r : out.reasons()) {
				String reason = r.reason().strip();
				byId.putIfAbsent(r.id().strip().toUpperCase(java.util.Locale.ROOT),
						reason.length() > 200 ? reason.substring(0, 199) + "…" : reason);
			}
			return byId;
		}
		catch (NoModelForSlotException | ProviderUnavailableException | InvalidLlmOutputException e) {
			return Map.of(); // the conflicts are still shown, just without a guessed reason
		}
	}

}
