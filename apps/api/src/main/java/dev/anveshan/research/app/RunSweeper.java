package dev.anveshan.research.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.research.infra.ResearchProperties;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.StepStore;

/**
 * Makes runs survive crashes (AC-E1) and AI limits (AC-E2). Every {@code sweep-interval}:
 * <ol>
 * <li>RUNNING steps older than {@code stale-after} are treated as crashed and re-queued;</li>
 * <li>runs with nothing in flight are settled — a crash between saving a DONE step and opening the next stage, or
 * a step that failed for good, can't leave a run stuck;</li>
 * <li>PAUSED(AI_LIMIT_REACHED) runs whose reset time has passed are resumed;</li>
 * <li>every {@code requeue-interval}, PENDING steps of active runs get a job again — a lost hand-off (crash between
 * commit and enqueue) or a claim that hit a DB error never strands a step. A duplicate job is harmless: claiming
 * is atomic.</li>
 * </ol>
 * At startup (single instance: no other worker is alive) every RUNNING step is recovered at once and every PENDING
 * one re-queued. Each run is handled on its own, so one broken run can't stop the sweep for the others.
 */
@Component
class RunSweeper {

	private static final Logger log = LoggerFactory.getLogger(RunSweeper.class);

	private final RunEngine engine;
	private final RunStore runs;
	private final StepStore steps;
	private final StepQueue queue;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final ResearchProperties props;
	private final TaskScheduler scheduler;
	/** When this process started: anything RUNNING before then belongs to a worker that no longer exists. */
	private final Instant bootedAt;
	volatile Instant lastRequeue;

	RunSweeper(RunEngine engine, RunStore runs, StepStore steps, StepQueue queue, TransactionTemplate researchTx,
			Clock clock, ResearchProperties props, TaskScheduler scheduler) {
		this.engine = engine;
		this.runs = runs;
		this.steps = steps;
		this.queue = queue;
		this.tx = researchTx;
		this.clock = clock;
		this.props = props;
		this.scheduler = scheduler;
		this.bootedAt = clock.instant(); // before JobRunr's server starts claiming (whatever the listener order)
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		guarded("startup recovery", () -> recoverStale(bootedAt)); // no step claimed before boot can still be running
		guarded("startup re-queue", this::requeuePending);
		sweep();
		scheduler.scheduleWithFixedDelay(this::sweep, props.sweepInterval());
	}

	void sweep() {
		Instant now = clock.instant();
		guarded("stale-step recovery", () -> recoverStale(now.minus(props.staleAfter())));
		guarded("idle-run settle", this::settleIdle);
		guarded("auto-resume", () -> resumeDue(now));
		Instant last = lastRequeue;
		if (last == null || !now.isBefore(last.plus(props.requeueInterval()))) {
			guarded("periodic re-queue", this::requeuePending);
		}
	}

	/** RUNNING steps that started before {@code cutoff} are treated as crashed: back to PENDING and re-queued. */
	int recoverStale(Instant cutoff) {
		List<StepStore.Recovered> recovered = tx.execute(s -> steps.recoverStale(cutoff));
		eachRun(recovered.stream().map(StepStore.Recovered::stepId).toList(), stepId -> {
			log.info("Recovered stale step {}", stepId);
			queue.enqueue(stepId);
		});
		return recovered.size();
	}

	/** PENDING steps of PLANNING/RUNNING runs that aren't meant to be waiting get a (possibly duplicate) job again. */
	int requeuePending() {
		Instant now = clock.instant();
		lastRequeue = now;
		List<UUID> pending = steps.pendingOfActiveRuns(now, now.minus(props.requeueInterval()));
		eachRun(pending, queue::enqueue);
		if (!pending.isEmpty()) {
			log.info("Re-queued {} pending research steps", pending.size());
		}
		return pending.size();
	}

	/** Runs that execute steps but have nothing in flight get their stage settled (next stage, done, or FAILED). */
	int settleIdle() {
		List<UUID> idle = steps.idleRuns();
		eachRun(idle, engine::advance);
		return idle.size();
	}

	/** PAUSED(AI_LIMIT_REACHED) runs whose reset time has passed are resumed (re-checked under the run lock). */
	int resumeDue(Instant now) {
		int[] resumed = { 0 };
		eachRun(runs.dueForResume(now), runId -> {
			if (engine.autoResume(runId, now)) {
				resumed[0]++;
			}
		});
		return resumed[0];
	}

	/** One id at a time, each failure logged and skipped — a single broken run or step never blocks the rest. */
	private static void eachRun(List<UUID> ids, Consumer<UUID> action) {
		for (UUID id : ids) {
			try {
				action.accept(id);
			}
			catch (RuntimeException e) {
				log.warn("Research sweep skipped {} ({})", id, e.getClass().getSimpleName());
			}
		}
	}

	private static void guarded(String what, Runnable r) {
		try {
			r.run();
		}
		catch (RuntimeException e) {
			log.warn("Research sweep ({}) failed: {}", what, e.getClass().getSimpleName());
		}
	}

}
