package dev.anveshan.research.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.research.app.StageMaterializer.NewStep;
import dev.anveshan.research.app.StageMaterializer.RunSnapshot;
import dev.anveshan.research.app.StepHandler.NewEvent;
import dev.anveshan.research.app.StepHandler.StepResult;
import dev.anveshan.research.domain.ResearchEnums.PauseReason;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.ResearchEnums.StepStatus;
import dev.anveshan.research.domain.RunInvalidStateException;
import dev.anveshan.research.domain.RunProgress;
import dev.anveshan.research.domain.RunStateMachine;
import dev.anveshan.research.infra.ResearchProperties;
import dev.anveshan.research.infra.RunEvents;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.RunStore.RunRow;
import dev.anveshan.research.api.PlanExpired;
import dev.anveshan.research.api.RunCompleted;
import dev.anveshan.research.api.PlanReady;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.research.infra.StepStore.StageCounts;
import dev.anveshan.research.infra.StepStore.StepRow;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates runs (TECHSPEC §12.1–12.2): closes stages and materializes the next, pause/resume/cancel/retry,
 * AI-limit pauses. Every DB change is one short transaction that first takes the run's lock (event lock, then the
 * row where needed — always in that order, so engine transactions can't deadlock), and writes its events in the
 * same transaction. Crash recovery and auto-resume live in {@link RunSweeper}.
 */
@Component
public class RunEngine {

	private static final Logger log = LoggerFactory.getLogger(RunEngine.class);

	/** A claimed step and the run it belongs to. */
	record Claimed(StepRow step, RunRow run) {
	}

	private final RunStore runs;
	private final StepStore steps;
	private final RunEvents events;
	private final StepQueue queue;
	private final StageMaterializer materializer;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final JsonMapper json;
	private final ResearchProperties props;
	private final SubQuestionStore subQuestions;

	RunEngine(RunStore runs, StepStore steps, RunEvents events, StepQueue queue, StageMaterializer materializer,
			TransactionTemplate researchTx, Clock clock, JsonMapper json, ResearchProperties props,
			SubQuestionStore subQuestions) {
		this.subQuestions = subQuestions;
		this.runs = runs;
		this.steps = steps;
		this.events = events;
		this.queue = queue;
		this.materializer = materializer;
		this.tx = researchTx;
		this.clock = clock;
		this.json = json;
		this.props = props;
	}

	// ------------------------------------------------------------------ lifecycle (RunService)

	/** Stage 0 of a new run: its PLAN step. Call inside the transaction that inserted the run. */
	void startPlanning(UUID runId) {
		queue.enqueue(steps.insert(runId, 0, StepKind.PLAN, null, "{}"));
	}

	/** AWAITING_APPROVAL → RUNNING and the first research stage (the approve endpoint arrives in P10). */
	public void approve(UUID runId) {
		tx.executeWithoutResult(s -> {
			RunRow run = locked(runId);
			move(run, RunStatus.RUNNING, null, null);
			materializeAfter(run, steps.currentStage(runId));
		});
	}

	/** E-FR-2: takes effect after the running step finishes; queued steps release themselves. */
	void pause(UUID runId) {
		tx.executeWithoutResult(s -> move(locked(runId), RunStatus.PAUSED, PauseReason.USER, null));
	}

	/** PAUSED → RUNNING; continues from the next PENDING step of the current stage (DONE steps never re-run). */
	void resume(UUID runId) {
		tx.executeWithoutResult(s -> resumeLocked(locked(runId)));
	}

	/**
	 * Auto-resume after an AI limit: re-checked on the locked row, so a run the user paused or resumed meanwhile
	 * (or whose reset moved later) is left alone.
	 */
	boolean autoResume(UUID runId, Instant now) {
		return Boolean.TRUE.equals(tx.execute(s -> {
			RunRow run = locked(runId);
			if (run.status() != RunStatus.PAUSED || run.pauseReason() != PauseReason.AI_LIMIT_REACHED
					|| run.resumeAt() == null || run.resumeAt().isAfter(now)) {
				return false;
			}
			resumeLocked(run);
			return true;
		}));
	}

	/**
	 * D-FR-7: cancels a plan that has waited, untouched, since before {@code cutoff} (re-checked under the run lock,
	 * so an edit or approval that just happened wins). The notify module tells the user (PlanExpired, P20).
	 */
	boolean expirePlan(UUID runId, Instant cutoff) {
		return Boolean.TRUE.equals(tx.execute(s -> {
			RunRow run = locked(runId);
			if (run.status() != RunStatus.AWAITING_APPROVAL || !run.updatedAt().isBefore(cutoff)) {
				return false;
			}
			move(run, RunStatus.CANCELLED, null, null);
			events.emit(runId, "plan.expired", Map.of("reason", "Not approved within the waiting time"));
			events.publish(new PlanExpired(runId, run.ownerId()));
			return true;
		}));
	}

	void cancel(UUID runId) {
		tx.executeWithoutResult(s -> move(locked(runId), RunStatus.CANCELLED, null, null));
	}

	/**
	 * E-FR-4: only a FAILED research step; the run goes back to RUNNING and the stage carries on. A failed PLAN
	 * can't be retried this way: PRD §8.7 has no FAILED → PLANNING move, so it would skip plan approval.
	 */
	void retryStep(UUID runId, UUID stepId) {
		tx.executeWithoutResult(s -> {
			RunRow run = locked(runId);
			StepRow step = steps.find(stepId).filter(st -> st.runId().equals(runId)).orElseThrow(StepNotRetryableException::new);
			if (step.status() != StepStatus.FAILED) {
				throw new StepNotRetryableException();
			}
			if (step.kind() == StepKind.PLAN) {
				throw new StepNotRetryableException("Planning failed. Start a new research to plan it again.");
			}
			if (run.status() != RunStatus.FAILED && run.status() != RunStatus.RUNNING) {
				throw new RunInvalidStateException(run.status(), RunStatus.RUNNING);
			}
			steps.resetFailed(runId, stepId);
			if (run.status() == RunStatus.FAILED) {
				move(run, RunStatus.RUNNING, null, null);
			}
			events.emit(runId, "step.retry", Map.of("stepId", stepId));
			continueStage(runs.find(runId).orElseThrow());
		});
	}

	// ------------------------------------------------------------------ step callbacks (StepWorker)

	/** Claims the step if its run executes steps; otherwise gives it straight back (paused/cancelled run). */
	Optional<Claimed> claim(UUID stepId) {
		return tx.execute(s -> {
			Optional<UUID> runId = steps.runOf(stepId);
			if (runId.isEmpty()) {
				return Optional.<Claimed>empty();
			}
			runs.lockEvents(runId.get()); // same lock order as every other engine transaction: run lock → step rows
			Optional<StepRow> step = steps.claim(stepId, clock.instant());
			if (step.isEmpty()) {
				return Optional.<Claimed>empty();
			}
			RunRow run = runs.find(step.get().runId()).orElseThrow();
			if (!RunStateMachine.executesSteps(run.status())) {
				steps.release(step.get());
				return Optional.<Claimed>empty();
			}
			events.emit(run.id(), "step.started", stepPayload(step.get()));
			return Optional.of(new Claimed(step.get(), run));
		});
	}

	/** Saves the checkpoint and the step's events in one transaction. False if this worker lost its claim. */
	boolean onDone(StepRow step, StepResult result) {
		return Boolean.TRUE.equals(tx.execute(s -> {
			runs.lockEvents(step.runId());
			if (!steps.complete(step, json.writeValueAsString(result.output()), clock.instant())) {
				return false; // recovered as crashed meanwhile: the other attempt owns the step now
			}
			events.emit(step.runId(), "step.completed", stepPayload(step));
			for (NewEvent e : result.events()) {
				events.emit(step.runId(), e.type(), e.payload());
			}
			StageCounts c = steps.counts(step.runId(), step.stage());
			BigDecimal progress = runs.raiseProgress(step.runId(),
					RunProgress.of(step.stage(), c.total() - c.open() - c.failed(), c.total()));
			events.emit(step.runId(), "run.progress", Map.of("progress", progress));
			return true;
		}));
	}

	/** All AI routes are cooling down: give the step back and pause until the earliest reset (AC-E2). */
	void onAiLimit(StepRow step, Instant resumeAt) {
		Instant at = resumeAt != null ? resumeAt : clock.instant().plus(props.aiLimitFallback());
		tx.executeWithoutResult(s -> {
			RunRow run = locked(step.runId());
			if (!steps.release(step)) {
				return;
			}
			if (run.status() == RunStatus.RUNNING) {
				move(run, RunStatus.PAUSED, PauseReason.AI_LIMIT_REACHED, at);
			}
			else if (run.status() == RunStatus.PLANNING) {
				// planning has no PAUSED state (PRD §8.7): tell the user and try the PLAN step again at the reset
				events.emit(run.id(), "log", Map.of("level", "WARN", "code", "AI_LIMIT_REACHED", "resumeAt", at.toString(),
						"message", "Your AI keys are rate-limited. Planning continues automatically when they reset."));
				runs.waitUntil(run.id(), at); // the periodic re-queue leaves the PLAN step alone until then
				queue.enqueueAt(step.id(), at);
			}
		});
	}

	/** Retryable failures wait and try again up to max-attempts; then the step and the run are FAILED. */
	void onFailure(StepRow step, String safeError, boolean retryable) {
		tx.executeWithoutResult(s -> {
			RunRow run = locked(step.runId());
			boolean again = retryable && step.attempts() < props.maxAttempts();
			Map<String, Object> payload = stepPayload(step);
			payload.put("error", safeError);
			payload.put("willRetry", again);
			if (again) {
				if (steps.retryLater(step, safeError, clock.instant())) {
					events.emit(step.runId(), "step.failed", payload);
					queue.enqueueAt(step.id(), clock.instant().plus(props.backoffBefore(step.attempts() + 1)));
				}
				return;
			}
			if (!steps.failFinal(step, safeError, clock.instant())) {
				return;
			}
			events.emit(step.runId(), "step.failed", payload);
			if (RunStateMachine.canMove(run.status(), RunStatus.FAILED)) {
				move(run, RunStatus.FAILED, null, null);
			}
			// a PAUSED run keeps its FAILED step; resuming it then fails the run (continueStage)
		});
	}

	// ------------------------------------------------------------------ advancing

	/**
	 * Settles the current stage when nothing is in flight: every step DONE/SKIPPED → PLAN goes to
	 * AWAITING_APPROVAL, otherwise the next stage opens; some step FAILED for good → the run FAILED.
	 */
	void advance(UUID runId) {
		tx.executeWithoutResult(s -> {
			RunRow run = runs.lock(runId).orElse(null);
			if (run != null && RunStateMachine.executesSteps(run.status())) {
				continueStage(run, false);
			}
		});
	}

	private void resumeLocked(RunRow run) {
		if (run.status() != RunStatus.PAUSED) {
			throw new RunInvalidStateException(run.status(), RunStatus.RUNNING);
		}
		move(run, RunStatus.RUNNING, null, null);
		continueStage(runs.find(run.id()).orElseThrow());
	}

	/** Resume/retry: re-queue what's pending, or settle the stage if nothing is. */
	private void continueStage(RunRow run) {
		continueStage(run, true);
	}

	private void continueStage(RunRow run, boolean enqueuePending) {
		int stage = steps.currentStage(run.id());
		if (stage < 0) {
			return;
		}
		StageCounts c = steps.counts(run.id(), stage);
		if (c.open() > 0) {
			if (enqueuePending) {
				steps.pendingIn(run.id(), stage).forEach(queue::enqueue);
			}
			return;
		}
		if (c.failed() > 0) {
			move(run, RunStatus.FAILED, null, null); // nothing left to wait for, and a step failed for good
			return;
		}
		if (run.status() == RunStatus.PLANNING) {
			move(run, RunStatus.AWAITING_APPROVAL, null, null);
			// same transaction as the status change; listeners (notifications, P20) run after commit
			events.publish(new PlanReady(run.id(), run.ownerId(), subQuestions.count(run.id())));
			return;
		}
		materializeAfter(run, stage);
	}

	private void materializeAfter(RunRow run, int closedStage) {
		List<NewStep> next = materializer.next(new RunSnapshot(run.id(), run.ownerId(), run.topic(), run.configJson()),
				closedStage);
		if (next.isEmpty()) {
			move(runs.find(run.id()).orElseThrow(), RunStatus.COMPLETED, null, null);
			events.publish(new RunCompleted(run.id(), run.ownerId())); // same tx; listeners run after commit
			return;
		}
		for (NewStep n : next) {
			queue.enqueue(steps.insert(run.id(), closedStage + 1, n.kind(), n.subQuestionId(),
					json.writeValueAsString(n.input())));
		}
	}

	// ------------------------------------------------------------------ helpers

	private RunRow locked(UUID runId) {
		return runs.lock(runId).orElseThrow(RunNotFoundException::new);
	}

	/** State-machine-checked move + its {@code run.status} event, in the caller's transaction. */
	private void move(RunRow run, RunStatus to, PauseReason reason, Instant resumeAt) {
		RunStateMachine.require(run.status(), to);
		if (!runs.move(run.id(), to, reason, resumeAt, clock.instant())) {
			throw new RunInvalidStateException(run.status(), to);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("status", to.name());
		payload.put("pauseReason", to == RunStatus.PAUSED && reason != null ? reason.name() : null);
		payload.put("resumeAt", to == RunStatus.PAUSED && resumeAt != null ? resumeAt.toString() : null);
		events.emit(run.id(), "run.status", payload);
		log.info("Run {} {} → {}", run.id(), run.status(), to);
	}

	private static Map<String, Object> stepPayload(StepRow step) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("stepId", step.id());
		p.put("kind", step.kind().name());
		p.put("stage", step.stage());
		p.put("attempt", step.attempts());
		return p;
	}

	/** The run vanished (deleted with its owner) between steps. */
	static class RunNotFoundException extends RuntimeException {

		RunNotFoundException() {
			super("Run not found");
		}

	}

	/** Retry asked for a step that isn't a FAILED research step (E-FR-4). */
	public static class StepNotRetryableException extends RuntimeException {

		StepNotRetryableException() {
			this("Only a failed step can be retried.");
		}

		StepNotRetryableException(String message) {
			super(message);
		}

	}

}
