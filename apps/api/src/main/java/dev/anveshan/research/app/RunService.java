package dev.anveshan.research.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.RouteQuery;
import dev.anveshan.report.api.Reports;
import dev.anveshan.sources.api.SourceLibrary;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.domain.ResearchEnums.PauseReason;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.ResearchEnums.StepStatus;
import dev.anveshan.research.domain.RunStateMachine;
import dev.anveshan.research.infra.RunEvents;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.RunStore.RunRow;
import dev.anveshan.research.infra.SseHub;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.research.infra.TemplateStore;
import dev.anveshan.research.infra.TemplateStore.TemplateRow;
import dev.anveshan.shared.web.ApiException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Research runs for the signed-in user (TECHSPEC §8.5, Modules C/E). Every entry point checks ownership in SQL
 * first — someone else's run is a 404 (RULES rule 6) — then hands the action to {@link RunEngine}.
 */
@Service
public class RunService {

	/** The New Research form (PRD Module C): the topic and the options the user chose (gaps = defaults). */
	public record CreateCommand(String topic, RunConfig options) {
	}

	/** A report template for the New Research form (C-FR-5). */
	public record TemplateView(String code, String name, Map<String, Object> formDefaults, List<String> sections) {
	}

	public record Created(RunView run, boolean existing) {
	}

	public record StepView(UUID id, int seq, int stage, StepKind kind, StepStatus status, int attempts,
			UUID subQuestionId, String lastError) {
	}

	public record RunView(UUID id, RunStatus status, PauseReason pauseReason, Instant resumeAt, BigDecimal progress,
			String topic, Map<String, Object> config, List<PlanService.SubQuestionView> subQuestions,
			PlanService.EstimateView estimate, List<StepView> steps, int version, Instant createdAt, Instant updatedAt,
			Instant completedAt, UUID reportId) {
	}

	/** One run in the list (dashboard, research history). */
	public record RunSummary(UUID id, String topic, RunStatus status, BigDecimal progress, Instant createdAt,
			Instant updatedAt, Instant completedAt, UUID reportId) {
	}

	public record RunPage(List<RunSummary> items, String nextCursor) {
	}

	private record Section(String key, String title, String instructions) {
	}

	private static final TypeReference<List<Section>> SECTIONS = new TypeReference<>() {
	};

	static final Duration IDEMPOTENCY_WINDOW = Duration.ofHours(24);

	private static final java.util.regex.Pattern UUID_TEXT = java.util.regex.Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final RunStore runs;
	private final StepStore steps;
	private final RunEngine engine;
	private final RunEvents events;
	private final SseHub hub;
	private final TransactionTemplate tx;
	private final JsonMapper json;
	private final Clock clock;
	private final TemplateStore templates;
	private final RouteQuery routes;
	private final PlanService plans;
	private final Reports reports;
	private final SourceLibrary sources;

	RunService(RunStore runs, StepStore steps, RunEngine engine, RunEvents events, SseHub hub,
			TransactionTemplate researchTx, JsonMapper json, Clock clock, TemplateStore templates, RouteQuery routes,
			PlanService plans, Reports reports, SourceLibrary sources) {
		this.reports = reports;
		this.sources = sources;
		this.templates = templates;
		this.routes = routes;
		this.plans = plans;
		this.runs = runs;
		this.steps = steps;
		this.engine = engine;
		this.events = events;
		this.hub = hub;
		this.tx = researchTx;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Starts a run in PLANNING with its PLAN step queued. With an {@code Idempotency-Key}, the same key from the
	 * same user returns the run it created the first time — no second run, no second PLAN step (TECHSPEC §8.1).
	 */
	public Created create(UUID owner, CreateCommand cmd, String idempotencyKey) {
		String topic = cmd.topic() == null ? "" : cmd.topic().strip();
		int chars = topic.codePointCount(0, topic.length()); // the DB CHECK counts characters, not UTF-16 units
		if (chars < 10 || chars > 500) {
			throw ApiException.badRequest("The topic must be 10–500 characters.");
		}
		String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
		if (key != null) {
			if (!UUID_TEXT.matcher(key).matches()) { // TECHSPEC §8.1: the key is a UUID (UUID.fromString is lenient)
				throw ApiException.badRequest("Idempotency-Key must be a UUID.");
			}
			key = key.toLowerCase(java.util.Locale.ROOT);
			// a key is remembered for 24 h; after that the same key starts a new run
			runs.forgetIdempotencyKeyOlderThan(owner, key, clock.instant().minus(IDEMPOTENCY_WINDOW));
			var existing = runs.findByIdempotencyKey(owner, key);
			if (existing.isPresent()) {
				return new Created(get(owner, existing.get()), true);
			}
		}
		// C-FR-3: planning and writing need an AI model before anything starts
		Set<TaskSlot> ready = routes.readySlots(owner);
		for (TaskSlot needed : List.of(TaskSlot.PLAN, TaskSlot.WRITE)) {
			if (!ready.contains(needed)) {
				throw new NoModelForSlotException(needed);
			}
		}
		RunConfig options = cmd.options() == null ? RunConfig.empty() : cmd.options();
		Optional<TemplateRow> template = Optional.empty();
		if (options.templateCode() != null) {
			template = templates.byCode(options.templateCode());
			if (template.isEmpty()) {
				throw ApiException.badRequest("Unknown template: " + options.templateCode() + ".");
			}
			options = options.withDefaults(json.readValue(template.get().formDefaultsJson(), RunConfig.class));
		}
		RunConfig checked;
		try {
			checked = options.resolved().validated(java.time.LocalDate.now(clock).getYear());
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest(e.getMessage());
		}
		String config = json.writeValueAsString(checked.toMap());
		UUID templateId = template.map(TemplateRow::id).orElse(null);
		UUID id = UUID.randomUUID();
		String finalKey = key;
		try {
			tx.executeWithoutResult(s -> {
				runs.insert(id, owner, topic, config, templateId, finalKey, clock.instant());
				events.emit(id, "run.status", Map.of("status", RunStatus.PLANNING.name()));
				engine.startPlanning(id);
			});
		}
		catch (DuplicateKeyException e) {
			// the same key raced in from a second request: answer with the run that won
			UUID winner = runs.findByIdempotencyKey(owner, finalKey).orElseThrow(() -> e);
			return new Created(get(owner, winner), true);
		}
		return new Created(get(owner, id), false);
	}

	public RunView get(UUID owner, UUID id) {
		RunRow r = runs.findOwned(owner, id).orElseThrow(ApiException::notFound);
		List<StepView> stepViews = steps.forRun(id).stream()
				.map(s -> new StepView(s.id(), s.seq(), s.stage(), s.kind(), s.status(), s.attempts(),
						s.subQuestionId(), s.lastError()))
				.toList();
		return new RunView(r.id(), r.status(), r.pauseReason(), r.resumeAt(), r.progress(), r.topic(),
				json.readValue(r.configJson(), MAP), plans.plan(id), plans.estimate(r), stepViews, r.version(),
				r.createdAt(), r.updatedAt(), r.completedAt(),
				r.status() == RunStatus.COMPLETED ? reports.reportIdForRun(id).orElse(null) : null);
	}

	/** The caller's runs, newest first; {@code cursor} is the opaque value of the previous page's nextCursor. */
	public RunPage list(UUID owner, String cursor, int limit) {
		int size = Math.clamp(limit, 1, 50);
		Instant ts = null;
		UUID id = null;
		if (cursor != null && !cursor.isBlank()) {
			try {
				String[] parts = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
						.split("\\|", 2);
				ts = Instant.parse(parts[0]);
				id = UUID.fromString(parts[1]);
			}
			catch (RuntimeException e) {
				throw ApiException.badRequest("This page link is no longer valid. Load the list again.");
			}
		}
		List<RunRow> rows = runs.list(owner, ts, id, size);
		List<RunSummary> items = new java.util.ArrayList<>();
		for (RunRow r : rows.subList(0, Math.min(size, rows.size()))) {
			items.add(new RunSummary(r.id(), r.topic(), r.status(), r.progress(), r.createdAt(), r.updatedAt(),
					r.completedAt(), r.status() == RunStatus.COMPLETED ? reports.reportIdForRun(r.id()).orElse(null) : null));
		}
		String next = null;
		if (rows.size() > size) {
			RunRow last = rows.get(size - 1);
			next = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
					(last.createdAt() + "|" + last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		return new RunPage(items, next);
	}

	/** E-FR-5 / S-07: the run's sources with their status and tier. */
	public List<SourceLibrary.RunSource> sources(UUID owner, UUID id) {
		return sources.runSources(owned(owner, id));
	}

	/**
	 * E-FR-5: skip a source while the run is still working — its claims stay out of the report. After the report
	 * is written (or the run stopped) there is nothing left to skip: 409 RUN_INVALID_STATE.
	 */
	public void skipSource(UUID owner, UUID id, UUID sourceId) {
		RunRow run = runs.findOwned(owner, id).orElseThrow(ApiException::notFound);
		if (run.status() != RunStatus.RUNNING && run.status() != RunStatus.PAUSED) {
			throw new dev.anveshan.research.domain.RunInvalidStateException(run.status(), run.status());
		}
		if (!sources.skip(id, sourceId)) {
			throw ApiException.notFound();
		}
		tx.executeWithoutResult(s -> events.emit(id, "source.skipped", Map.of("sourceId", sourceId)));
	}

	/** The four report templates (public data, C-FR-5). */
	public List<TemplateView> templates() {
		return templates.all().stream()
				.map(t -> new TemplateView(t.code(), t.name(), json.readValue(t.formDefaultsJson(), MAP),
						json.readValue(t.sectionsJson(), SECTIONS).stream().map(Section::title).toList()))
				.toList();
	}

	public void pause(UUID owner, UUID id) {
		engine.pause(owned(owner, id));
	}

	public void resume(UUID owner, UUID id) {
		engine.resume(owned(owner, id));
	}

	public void cancel(UUID owner, UUID id) {
		engine.cancel(owned(owner, id));
	}

	public void retryStep(UUID owner, UUID id, UUID stepId) {
		engine.retryStep(owned(owner, id), stepId);
	}

	/** Live events of the caller's own run; replays everything after {@code lastEventId} first (E-FR-1). */
	public SseEmitter events(UUID owner, UUID id, long lastEventId) {
		RunRow run = runs.findOwned(owner, id).orElseThrow(ApiException::notFound);
		return hub.open(run.id(), lastEventId, RunStateMachine.isFinal(run.status()));
	}

	private UUID owned(UUID owner, UUID id) {
		return runs.findOwned(owner, id).map(RunRow::id).orElseThrow(ApiException::notFound);
	}

}
