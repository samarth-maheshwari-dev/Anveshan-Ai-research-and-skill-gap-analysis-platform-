package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.byok.api.RouteQuery;
import dev.anveshan.byok.api.RouteQuery.FirstRoute;
import dev.anveshan.research.domain.Estimate;
import dev.anveshan.research.domain.PlanExceptions.PlanLockedException;
import dev.anveshan.research.domain.PlanExceptions.TooManyRunsException;
import dev.anveshan.research.domain.PlanExceptions.VersionConflictException;
import dev.anveshan.research.domain.PlanRules;
import dev.anveshan.research.domain.PlanRules.PlannedQuestion;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.infra.RunEvents;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.RunStore.RunRow;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.research.infra.SubQuestionStore.SubQuestionRow;
import dev.anveshan.research.infra.TemplateStore;
import dev.anveshan.shared.web.ApiException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan Review (Module D): edit the plan while it waits for approval, approve it, and estimate what it will cost
 * (D-FR-2, TECHSPEC §12.3). Every entry point is owner-scoped; edits and approval check the version the user saw.
 */
@Service
public class PlanService {

	/** TECHSPEC §12.2: a user runs at most this many researches at once (RUNNING or PAUSED). */
	static final int MAX_ACTIVE_RUNS = 2;

	/** Sections of a report without a template (one WRITE call each). */
	static final int DEFAULT_SECTIONS = 5;

	/** A card as the user sent it: {@code id} keeps an edited card's identity; new cards have none. */
	public record PlanEdit(UUID id, String text, List<SourceType> sourceTypes, List<String> queries) {
	}

	public record SubQuestionView(UUID id, int position, String text, List<SourceType> sourceTypes,
			List<String> queries) {
	}

	/** One AI connection's share of the estimate; {@code pctOfTodayRemaining} is null when the limit is unknown. */
	public record ConnectionShare(UUID connectionId, String label, String provider, int requests, int usedToday,
			Integer remainingToday, Double pctOfTodayRemaining) {
	}

	/** Which connection would do a slot's work (its first verified route). */
	public record SlotRoute(String slot, UUID connectionId) {
	}

	/**
	 * D-FR-2. {@code sections} and {@code routes} let the browser recompute the estimate on every edit without a
	 * request (D-FR-4); {@code unroutedSlots} names work that has no AI model yet.
	 */
	public record EstimateView(Map<String, Integer> requests, int totalRequests, int minutes, int sections,
			List<ConnectionShare> perConnection, List<SlotRoute> routes, List<String> unroutedSlots) {
	}

	private record Section(String key, String title, String instructions) {
	}

	private static final TypeReference<List<Section>> SECTIONS = new TypeReference<>() {
	};

	private final RunStore runs;
	private final SubQuestionStore subQuestions;
	private final TemplateStore templates;
	private final RouteQuery routes;
	private final RunEngine engine;
	private final RunEvents events;
	private final TransactionTemplate tx;
	private final JsonMapper json;

	PlanService(RunStore runs, SubQuestionStore subQuestions, TemplateStore templates, RouteQuery routes,
			RunEngine engine, RunEvents events, TransactionTemplate researchTx, JsonMapper json) {
		this.runs = runs;
		this.subQuestions = subQuestions;
		this.templates = templates;
		this.routes = routes;
		this.engine = engine;
		this.events = events;
		this.tx = researchTx;
		this.json = json;
	}

	public List<SubQuestionView> plan(UUID runId) {
		return subQuestions.forRun(runId).stream()
				.map(q -> new SubQuestionView(q.id(), q.position(), q.text(), q.sourceTypes(), q.queries())).toList();
	}

	/** Null while there is no plan yet (PLANNING). */
	public EstimateView estimate(RunRow run) {
		int count = subQuestions.count(run.id());
		if (count == 0) {
			return null;
		}
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		int sections = config.templateCode() == null ? DEFAULT_SECTIONS
				: templates.byCode(config.templateCode()).map(t -> json.readValue(t.sectionsJson(), SECTIONS).size())
						.filter(n -> n > 0).orElse(DEFAULT_SECTIONS);
		Estimate e = Estimate.of(config.depth(), count, sections, 0.0);

		Map<String, FirstRoute> bySlot = new LinkedHashMap<>();
		for (FirstRoute r : routes.firstRoutes(run.ownerId())) {
			bySlot.put(r.slot().name(), r);
		}
		Map<UUID, int[]> perConnection = new LinkedHashMap<>();
		Map<UUID, FirstRoute> sample = new LinkedHashMap<>();
		List<String> unrouted = new ArrayList<>();
		List<SlotRoute> slotRoutes = new ArrayList<>();
		e.requests().forEach((slot, n) -> {
			FirstRoute r = bySlot.get(slot);
			if (r == null) {
				unrouted.add(slot);
				return;
			}
			slotRoutes.add(new SlotRoute(slot, r.connectionId()));
			perConnection.computeIfAbsent(r.connectionId(), k -> new int[1])[0] += n;
			sample.putIfAbsent(r.connectionId(), r);
		});
		List<ConnectionShare> shares = new ArrayList<>();
		perConnection.forEach((id, n) -> {
			FirstRoute r = sample.get(id);
			Integer remaining = r.remainingToday();
			Double pct = remaining == null || remaining <= 0 ? null
					: Math.round(1000.0 * n[0] / remaining) / 10.0; // one decimal, e.g. 4.2 (%)
			shares.add(new ConnectionShare(id, r.connectionLabel(), r.provider(), n[0], r.usedToday(), remaining, pct));
		});
		return new EstimateView(e.requests(), e.totalRequests(), e.minutes(), sections, List.copyOf(shares),
				List.copyOf(slotRoutes), List.copyOf(unrouted));
	}

	/**
	 * D-FR-3: replaces the plan (edit, delete, add, reorder) while the run awaits approval. {@code expectedVersion}
	 * is the {@code If-Match} the user saw; a different current version → 409 VERSION_CONFLICT (TECHSPEC §8.1).
	 */
	public void updatePlan(UUID owner, UUID runId, Integer expectedVersion, List<PlanEdit> edits) {
		requireOwned(owner, runId);
		tx.executeWithoutResult(s -> {
			RunRow run = runs.lock(runId).orElseThrow(ApiException::notFound);
			if (run.status() != RunStatus.AWAITING_APPROVAL) {
				throw new PlanLockedException(); // a locked plan stays locked, whatever version the user saw
			}
			checkVersion(run, expectedVersion);
			RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
			Set<UUID> existing = new java.util.HashSet<>(subQuestions.forRun(runId).stream().map(SubQuestionRow::id).toList());
			List<PlannedQuestion> plan;
			List<UUID> ids = new ArrayList<>();
			try {
				List<PlannedQuestion> raw = new ArrayList<>();
				for (PlanEdit e : edits == null ? List.<PlanEdit>of() : edits) {
					if (e.id() != null && !existing.contains(e.id())) {
						throw new IllegalArgumentException("A sub-question in your edit doesn't belong to this plan.");
					}
					raw.add(new PlannedQuestion(e.text(), e.sourceTypes(), e.queries()));
					ids.add(e.id());
				}
				plan = PlanRules.normalize(raw, config.sourceSet());
			}
			catch (IllegalArgumentException e) {
				throw ApiException.badRequest(e.getMessage());
			}
			subQuestions.replace(runId, plan, ids);
			runs.bumpVersion(runId);
			events.emit(runId, "plan.updated", Map.of("subQuestions", plan.size()));
		});
	}

	/** D-FR-5: approve the plan the user saw → RUNNING, first research stage queued. */
	public void approve(UUID owner, UUID runId, Integer expectedVersion) {
		requireOwned(owner, runId);
		tx.executeWithoutResult(s -> {
			RunRow run = runs.lock(runId).orElseThrow(ApiException::notFound);
			if (run.status() != RunStatus.AWAITING_APPROVAL) {
				throw new PlanLockedException();
			}
			checkVersion(run, expectedVersion);
			if (subQuestions.count(runId) < PlanRules.MIN_SUB_QUESTIONS) {
				throw ApiException.badRequest("Add at least one sub-question before you start.");
			}
			if (runs.activeCount(owner) >= MAX_ACTIVE_RUNS) {
				throw new TooManyRunsException(MAX_ACTIVE_RUNS);
			}
			engine.approve(runId); // joins this transaction (same run lock)
		});
	}

	private static void checkVersion(RunRow run, Integer expectedVersion) {
		if (expectedVersion == null) {
			throw ApiException.badRequest("Send the plan's version in the If-Match header.");
		}
		if (expectedVersion != run.version()) {
			throw new VersionConflictException(run.version());
		}
	}

	private void requireOwned(UUID owner, UUID runId) {
		runs.findOwned(owner, runId).orElseThrow(ApiException::notFound);
	}

}
