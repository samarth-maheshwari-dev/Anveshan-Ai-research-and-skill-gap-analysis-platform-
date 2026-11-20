package dev.anveshan.research.app;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.RouteQuery;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.report.api.Reports;
import dev.anveshan.report.api.Reports.ReportRef;
import dev.anveshan.report.api.Reports.SavedSection;
import dev.anveshan.report.api.Reports.SectionRef;
import dev.anveshan.report.api.Reports.SelectedText;
import dev.anveshan.research.domain.PlanExceptions.TooManyRunsException;
import dev.anveshan.research.domain.PlanRules.PlannedQuestion;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.research.infra.RunEvents;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.RunStore.RunRow;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.shared.web.ApiException;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.ReportClaim;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Report Studio actions that need the AI or the research engine (Module F): Assist on a selection (F-FR-4),
 * regenerate one section from its existing claims (F-FR-7) and Find better sources for one claim (F-FR-5). Every
 * call checks that the report is the caller's first (404 otherwise).
 */
@Service
public class ReportStudio {

	public enum AssistAction {
		SIMPLIFY, MORE_DETAIL, TRANSLATE_HI
	}

	record AssistOutput(@NotBlank @Size(max = 20_000) String markdown) {
	}

	static final String ASSIST_PROMPT = "assist";
	static final int ASSIST_PROMPT_VERSION = 1;
	/** About a page: Assist rewrites a passage, not a whole report. */
	static final int MAX_SELECTION = 6_000;

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final Reports reports;
	private final ClaimLibrary claims;
	private final RunStore runs;
	private final StepStore steps;
	private final SubQuestionStore subQuestions;
	private final RunEngine engine;
	private final RunEvents events;
	private final WriteHandler writer;
	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final RouteQuery routes;
	private final TransactionTemplate tx;
	private final JsonMapper json;
	private final Clock clock;

	ReportStudio(Reports reports, ClaimLibrary claims, RunStore runs, StepStore steps, SubQuestionStore subQuestions,
			RunEngine engine, RunEvents events, WriteHandler writer, LlmGateway gateway, PromptFiles prompts,
			RouteQuery routes, TransactionTemplate researchTx, JsonMapper json, Clock clock) {
		this.reports = reports;
		this.claims = claims;
		this.runs = runs;
		this.steps = steps;
		this.subQuestions = subQuestions;
		this.engine = engine;
		this.events = events;
		this.writer = writer;
		this.gateway = gateway;
		this.prompts = prompts;
		this.routes = routes;
		this.tx = researchTx;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * F-FR-4: a suggestion for the selected text as a TipTap fragment — the user accepts or discards it in the
	 * editor. Citations in the suggestion can only be claims that were in the selection.
	 */
	public Map<String, Object> assist(UUID ownerId, UUID reportId, UUID sectionId, int from, int to,
			AssistAction action) {
		ReportRef report = reports.owned(ownerId, reportId).orElseThrow(ApiException::notFound);
		editable(report, sectionId);
		SelectedText selection;
		try {
			selection = reports.select(report.id(), sectionId, from, to);
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest(e.getMessage());
		}
		if (selection.text().length() > MAX_SELECTION) {
			throw ApiException.badRequest("Select a shorter part — up to about a page — and try again.");
		}
		Map<String, UUID> aliases = new LinkedHashMap<>();
		String passage = selection.text();
		StringBuilder claimList = new StringBuilder();
		Map<UUID, ReportClaim> byId = new LinkedHashMap<>();
		claims.reportClaims(report.runId(), selection.claimIds()).forEach(c -> byId.put(c.id(), c));
		for (UUID id : selection.claimIds()) {
			String alias = "C" + (aliases.size() + 1);
			aliases.put(alias, id);
			passage = passage.replace("[c:" + id + "]", "[c:" + alias + "]");
			ReportClaim c = byId.get(id);
			if (c != null) {
				claimList.append(alias).append(" [").append(c.label()).append("]: ").append(Planner.data(c.text()))
						.append('\n');
			}
		}
		boolean hindi = action == AssistAction.TRANSLATE_HI || "hi".equals(report.language());
		Prompt prompt = prompts.load(ASSIST_PROMPT, ASSIST_PROMPT_VERSION,
				Map.of("action", action.name(), "language", hindi ? "Hindi" : "English", "passage",
						Planner.data(passage), "claims", claimList.isEmpty() ? "none" : claimList.toString().strip()),
				0.3, 2000);
		String markdown = gateway.call(ownerId, TaskSlot.WRITE, prompt, AssistOutput.class,
				CallContext.step(report.runId(), null)).value().markdown();
		Reports.MarkerCheck checked = reports.checkMarkers(markdown.replaceAll("(?m)^#+\\s+", "").strip(), aliases);
		return reports.fragment(report.id(), checked.markdown());
	}

	/**
	 * F-FR-7 / TECHSPEC §12.10: one WRITE call over the claims this section was written from (its WRITE_SECTION
	 * step's input), with their current labels — no search, no extraction. Saved as a new version.
	 */
	public SavedSection regenerate(UUID ownerId, UUID reportId, UUID sectionId, Integer expectedVersion) {
		ReportRef report = reports.owned(ownerId, reportId).orElseThrow(ApiException::notFound);
		SectionRef section = editable(report, sectionId);
		if (expectedVersion != null && expectedVersion != section.version()) {
			throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "VERSION_CONFLICT", "Changed meanwhile",
					"This section was saved somewhere else meanwhile. Reload it to see the latest text.")
					.withProperty("currentVersion", Integer.toString(section.version()));
		}
		StepStore.StepRow written = steps.forRun(report.runId()).stream()
				.filter(s -> s.kind() == StepKind.WRITE_SECTION
						&& section.key().equals(json.readTree(s.inputJson()).path("key").asString("")))
				.reduce((a, b) -> b)
				.orElseThrow(() -> ApiException.badRequest("This section can't be regenerated: it wasn't written from claims."));
		RunRow run = runs.find(report.runId()).orElseThrow(ApiException::notFound);
		WriteHandler.Written w = writer.write(ownerId, run, json.readTree(written.inputJson()),
				CallContext.step(run.id(), null));
		if (w.markdown() == null) {
			throw ApiException.badRequest("This section has no verified claims to write from.");
		}
		return reports.replaceSection(ownerId, report.id(), sectionId, expectedVersion, w.markdown());
	}

	/**
	 * F-FR-5 / TECHSPEC §12.10: a child run ({@code config.mode = CLAIM_REFRESH}, hidden from run lists) searches
	 * for the claim, judges it against the new passages and relabels it in the original run. Returns the child
	 * run's id; a refresh already working on this claim is returned instead of starting a second one.
	 */
	public UUID findBetterSources(UUID ownerId, UUID reportId, UUID claimId) {
		ReportRef report = reports.owned(ownerId, reportId).orElseThrow(ApiException::notFound);
		ReportClaim claim = claims.reportClaims(report.runId(), List.of(claimId)).stream().findFirst()
				.orElseThrow(ApiException::notFound);
		if (!routes.readySlots(ownerId).contains(TaskSlot.VERIFY)) {
			throw new NoModelForSlotException(TaskSlot.VERIFY);
		}
		RunRow parent = runs.find(report.runId()).orElseThrow(ApiException::notFound);
		var existing = runs.activeRefresh(ownerId, report.id(), claimId);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (runs.activeCount(ownerId) >= PlanService.MAX_ACTIVE_RUNS) {
			throw new TooManyRunsException(PlanService.MAX_ACTIVE_RUNS);
		}
		Map<String, Object> config = new LinkedHashMap<>(json.readValue(parent.configJson(), MAP));
		config.put("mode", ResearchStages.CLAIM_REFRESH);
		config.put("claimId", claimId.toString());
		config.put("reportId", report.id().toString());
		config.put("parentRunId", parent.id().toString());
		RunConfig options = json.readValue(parent.configJson(), RunConfig.class).resolved();
		String text = claim.text().strip().replaceAll("\\s+", " ");
		String topic = clip(text.length() < 10 ? "Evidence for: " + text : text, 500);
		List<SourceType> types = options.sources();
		PlannedQuestion question = new PlannedQuestion(clip(text, 500), types,
				List.of(clip(text, 200)));
		UUID id = UUID.randomUUID();
		tx.executeWithoutResult(s -> {
			runs.insert(id, ownerId, topic, json.writeValueAsString(config), null, null, clock.instant());
			subQuestions.replace(id, List.of(question), null);
			events.emit(id, "run.status", Map.of("status", RunStatus.PLANNING.name()));
			engine.startRefresh(id);
		});
		return id;
	}

	private SectionRef editable(ReportRef report, UUID sectionId) {
		SectionRef s = reports.section(report.id(), sectionId).orElseThrow(ApiException::notFound);
		if (!s.editable()) {
			throw ApiException.badRequest("This section is built by Anveshan from the sources and can't be changed here.");
		}
		return s;
	}

	private static String clip(String s, int max) {
		return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
	}

}
