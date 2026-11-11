package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.byok.api.AllRoutesExhaustedException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.RunEventStore;
import dev.anveshan.research.infra.StepStore;

/**
 * Bugs found by the P09 checker/reviewer, each with a test (RULES §12: next free id). Runs are seeded straight into
 * the tables to reproduce exact crash states.
 */
class EngineRecoveryIT extends ResearchITBase {

	@Autowired
	StepStore stepStore;

	@Autowired
	RunEventStore eventStore;

	@Autowired
	TransactionTemplate researchTx;

	UUID seedRun(RunStatus status, String pauseReason) {
		return jdbc.sql("""
				INSERT INTO research_run (owner_id, topic, config, status, pause_reason)
				VALUES (:o, 'Seeded run for recovery tests', '{}', :s, :p) RETURNING id""")
				.param("o", alice).param("s", status.name()).param("p", pauseReason).query(UUID.class).single();
	}

	UUID seedStep(UUID run, int seq, int stage, String kind, String status) {
		return jdbc.sql("""
				INSERT INTO research_step (run_id, seq, stage, kind, input, status, attempts, idempotency_key)
				VALUES (:r, :seq, :stage, :kind, '{}', :st, CASE WHEN :st = 'PENDING' THEN 0 ELSE 1 END, :r || ':' || :kind || ':' || :seq)
				RETURNING id""")
				.param("r", run).param("seq", seq).param("stage", stage).param("kind", kind).param("st", status)
				.query(UUID.class).single();
	}

	@Test
	@DisplayName("T-09-12 crash between saving the last DONE step and opening the next stage → the sweep settles the idle "
			+ "run: next stage runs, DONE steps never re-run, run COMPLETED (AC-E1, CHK-P09-01)")
	void idleClosedStageIsAdvanced() {
		UUID run = seedRun(RunStatus.RUNNING, null);
		seedStep(run, 0, 0, "PLAN", "DONE");
		UUID s1 = seedStep(run, 1, 1, "SEARCH", "DONE");
		UUID s2 = seedStep(run, 2, 1, "SEARCH", "DONE");

		awaitStatus(run, RunStatus.COMPLETED); // the 1 s sweep found it; nothing else was going to
		assertThat(steps(run, "FETCH")).hasSize(2);
		assertThat(steps(run, "RELEVANCE")).hasSize(1);
		assertThat(fake.callsOf(s1) + fake.callsOf(s2)).isZero();
	}

	@Test
	@DisplayName("T-09-13 a crash or DB error lost a PENDING step's job → the re-queue (startup and periodic) runs it and "
			+ "the run completes (REV-P09-02, CHK-P09-R2-01)")
	void lostJobIsRequeued() {
		UUID run = seedRun(RunStatus.RUNNING, null);
		seedStep(run, 0, 0, "PLAN", "DONE");
		UUID lost = seedStep(run, 1, 3, "RELEVANCE", "PENDING");

		assertThat(sweeper.requeuePending()).isGreaterThanOrEqualTo(1);
		awaitStatus(run, RunStatus.COMPLETED);
		assertThat(fake.callsOf(lost)).isEqualTo(1);
	}

	@Test
	@DisplayName("T-09-26 the periodic re-queue runs only once requeue-interval has passed, then picks up an orphaned "
			+ "step without a restart (CHK-P09-R3-01)")
	void periodicRequeueIsGated() {
		UUID run = seedRun(RunStatus.RUNNING, null);
		seedStep(run, 0, 0, "PLAN", "DONE");
		UUID orphan = seedStep(run, 1, 3, "RELEVANCE", "PENDING");

		sweeper.lastRequeue = Instant.now(); // re-queued just now (the interval is 1 h in tests)
		sweeper.sweep();
		assertThat(stepStatus(orphan)).as("not before the interval").isEqualTo("PENDING");
		assertThat(fake.callsOf(orphan)).isZero();

		sweeper.lastRequeue = Instant.now().minus(Duration.ofHours(2)); // the interval has passed
		sweeper.sweep();
		awaitStatus(run, RunStatus.COMPLETED);
		assertThat(fake.callsOf(orphan)).isEqualTo(1);
	}

	@Test
	@DisplayName("T-09-27 the periodic re-queue leaves deliberately waiting steps alone: a PLAN step waiting for an AI "
			+ "reset, a step waiting out its retry backoff (REV-P09-R3-03, CHK-P09-R3-02)")
	void requeueSkipsWaitingSteps() {
		UUID planning = seedRun(RunStatus.PLANNING, null);
		UUID plan = seedStep(planning, 0, 0, "PLAN", "PENDING");
		jdbc.sql("UPDATE research_run SET resume_at = now() + interval '1 hour' WHERE id = :r").param("r", planning)
				.update();
		UUID running = seedRun(RunStatus.RUNNING, null);
		UUID backingOff = seedStep(running, 0, 3, "RELEVANCE", "PENDING");
		jdbc.sql("UPDATE research_step SET finished_at = now(), last_error = 'x' WHERE id = :s").param("s", backingOff)
				.update();

		List<UUID> due = stepStore.pendingOfActiveRuns(Instant.now(), Instant.now().minus(Duration.ofMinutes(5)));
		assertThat(due).doesNotContain(plan, backingOff);
		List<UUID> later = stepStore.pendingOfActiveRuns(Instant.now().plus(Duration.ofHours(2)),
				Instant.now().plusSeconds(1));
		assertThat(later).contains(plan, backingOff);
		jdbc.sql("UPDATE research_run SET status = 'CANCELLED' WHERE id IN (:a, :b)").param("a", planning)
				.param("b", running).update();
	}

	@Test
	@DisplayName("T-09-28 one broken run (its next stage throws) doesn't stop the sweep from settling the others "
			+ "(REV-P09-R3-07)")
	void brokenRunDoesNotBlockSweep() {
		UUID broken = seedRun(RunStatus.RUNNING, null);
		seedStep(broken, 0, 0, "PLAN", "DONE");
		seedStep(broken, 1, 1, "SEARCH", "DONE");
		fake.brokenRuns.add(broken);
		UUID healthy = seedRun(RunStatus.RUNNING, null);
		seedStep(healthy, 0, 0, "PLAN", "DONE");
		seedStep(healthy, 1, 1, "SEARCH", "DONE");

		assertThat(sweeper.settleIdle()).isGreaterThanOrEqualTo(2);
		awaitStatus(healthy, RunStatus.COMPLETED);
		assertThat(status(broken)).isEqualTo(RunStatus.RUNNING);
		assertThat(steps(broken, "FETCH")).isEmpty();
		jdbc.sql("UPDATE research_run SET status = 'CANCELLED' WHERE id = :r").param("r", broken).update();
	}

	@Test
	@DisplayName("T-09-14 a step failed for good while the run was paused → resume makes the run FAILED (not stuck "
			+ "RUNNING); retry → COMPLETED (CHK-P09-02, REV-P09-03)")
	void failedDuringPauseThenResume() throws Exception {
		UUID run = seedRun(RunStatus.PAUSED, "USER");
		seedStep(run, 0, 0, "PLAN", "DONE");
		seedStep(run, 1, 1, "SEARCH", "DONE");
		UUID failed = seedStep(run, 2, 1, "SEARCH", "FAILED");

		var resume = perform(alice, post("/api/v1/runs/" + run + "/resume"));
		assertThat(resume.getResponse().getStatus()).as(body(resume)).isEqualTo(202);
		assertThat(status(run)).isEqualTo(RunStatus.FAILED);

		var retry = perform(alice, post("/api/v1/runs/" + run + "/steps/" + failed + "/retry"));
		assertThat(retry.getResponse().getStatus()).as(body(retry)).isEqualTo(202);
		awaitStatus(run, RunStatus.COMPLETED);
		assertThat(fake.callsOf(failed)).isEqualTo(1);
	}

	@Test
	@DisplayName("T-09-15 PLAN fails 3× → run FAILED; retrying the PLAN step → 409 and approval is never skipped "
			+ "(REV-P09-01)")
	void failedPlanCanNotSkipApproval() throws Exception {
		fake.on(StepKind.PLAN, (s, call) -> {
			throw new IllegalStateException("planner down");
		});
		UUID run = startRun(alice);
		awaitStatus(run, RunStatus.FAILED);
		UUID plan = steps(run, "PLAN").get(0);

		var retry = perform(alice, post("/api/v1/runs/" + run + "/steps/" + plan + "/retry"));
		assertThat(retry.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(retry).path("code").asString()).isEqualTo("RUN_INVALID_STATE");
		assertThat(jsonOf(retry).path("detail").asString()).contains("Start a new research");
		assertThat(status(run)).isEqualTo(RunStatus.FAILED);
		assertThat(steps(run, "SEARCH")).isEmpty();
	}

	@Test
	@DisplayName("T-09-16 a worker that lost its claim before a retry can't complete the retried step, even with the "
			+ "same attempt number (REV-P09-04)")
	void oldClaimCanNotOverwriteRetriedStep() {
		UUID run = seedRun(RunStatus.RUNNING, null);
		UUID step = seedStep(run, 0, 3, "RELEVANCE", "PENDING");
		// claim times in the future: the stale-step sweep (cutoff = now − 10 min) can never touch them mid-test
		Instant t = Instant.now().plus(Duration.ofHours(1));
		StepStore.StepRow oldClaim = stepStore.claim(step, t).orElseThrow();
		assertThat(stepStore.failFinal(oldClaim, "boom", t.plusSeconds(1))).isTrue();
		assertThat(stepStore.resetFailed(run, step)).isTrue();
		StepStore.StepRow newClaim = stepStore.claim(step, t.plusSeconds(300)).orElseThrow();
		assertThat(newClaim.attempts()).isEqualTo(oldClaim.attempts()); // same number — the claim time differs

		assertThat(stepStore.complete(oldClaim, "{\"from\":\"zombie\"}", Instant.now())).isFalse();
		assertThat(stepStore.complete(newClaim, "{\"from\":\"retry\"}", Instant.now())).isTrue();
		assertThat(jdbc.sql("SELECT output->>'from' FROM research_step WHERE id = :s").param("s", step)
				.query(String.class).single()).isEqualTo("retry");
	}

	@Test
	@DisplayName("T-09-17 auto-resume never resumes a run the user paused, nor one whose reset is still ahead "
			+ "(REV-P09-05)")
	void autoResumeRechecksUnderLock() {
		UUID userPaused = seedRun(RunStatus.PAUSED, "USER");
		jdbc.sql("UPDATE research_run SET resume_at = now() - interval '1 minute' WHERE id = :r").param("r", userPaused)
				.update();
		seedStep(userPaused, 0, 1, "SEARCH", "PENDING");
		assertThat(engine.autoResume(userPaused, Instant.now())).isFalse();
		assertThat(status(userPaused)).isEqualTo(RunStatus.PAUSED);

		UUID notYet = seedRun(RunStatus.PAUSED, "AI_LIMIT_REACHED");
		jdbc.sql("UPDATE research_run SET resume_at = now() + interval '1 hour' WHERE id = :r").param("r", notYet).update();
		seedStep(notYet, 0, 1, "SEARCH", "PENDING");
		assertThat(engine.autoResume(notYet, Instant.now())).isFalse();
		assertThat(status(notYet)).isEqualTo(RunStatus.PAUSED);
	}

	@Test
	@DisplayName("T-09-18 AI limit while planning → a visible AI_LIMIT_REACHED event within 5 s, PLAN retried after "
			+ "the reset, run reaches AWAITING_APPROVAL (REV-P09-07)")
	void aiLimitDuringPlanningIsVisible() throws Exception {
		AtomicReference<Instant> thrownAt = new AtomicReference<>();
		fake.on(StepKind.PLAN, (s, call) -> {
			if (call == 1) {
				thrownAt.set(Instant.now());
				throw new AllRoutesExhaustedException(TaskSlot.PLAN, Instant.now().plusSeconds(2));
			}
			return null;
		});
		UUID run = startRun(alice);
		await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.sql("""
				SELECT count(*) FROM run_event WHERE run_id = :r AND type = 'log' AND payload->>'code' = 'AI_LIMIT_REACHED'""")
				.param("r", run).query(Long.class).single()).isEqualTo(1));
		assertThat(jdbc.sql("SELECT count(*) FROM research_run WHERE id = :r AND resume_at IS NOT NULL").param("r", run)
				.query(Long.class).single()).as("the reset is recorded, so the re-queue waits for it").isEqualTo(1);
		var shownAt = jdbc.sql("""
				SELECT created_at FROM run_event WHERE run_id = :r AND type = 'log' AND payload->>'code' = 'AI_LIMIT_REACHED'""")
				.param("r", run).query(java.sql.Timestamp.class).single().toInstant();
		assertThat(Duration.between(thrownAt.get(), shownAt)).isLessThan(Duration.ofSeconds(5));
		awaitStatus(run, RunStatus.AWAITING_APPROVAL);
	}

	@Test
	@DisplayName("T-09-24 a 429 without a reset time pauses for anveshan.research.ai-limit-fallback (60 s), not forever "
			+ "(REV-P09-R2-10)")
	void aiLimitWithoutResetTime() throws Exception {
		fake.on(StepKind.SEARCH, (s, call) -> {
			throw new AllRoutesExhaustedException(TaskSlot.EXTRACT, null);
		});
		UUID run = startApprovedRun(alice);
		awaitStatus(run, RunStatus.PAUSED);
		Instant resumeAt = jdbc.sql("SELECT resume_at FROM research_run WHERE id = :r").param("r", run)
				.query(java.sql.Timestamp.class).single().toInstant();
		assertThat(resumeAt).isBetween(Instant.now().plusSeconds(40), Instant.now().plusSeconds(61));
		assertThat(perform(alice, post("/api/v1/runs/" + run + "/cancel")).getResponse().getStatus()).isEqualTo(202);
	}

	@Test
	@DisplayName("T-09-19 two transactions writing events for one run: the second waits until the first commits, so "
			+ "the run's event ids appear in commit order (SSE never skips one — REV-P09-15)")
	void eventWritersOfOneRunAreSerialized() throws Exception {
		UUID run = seedRun(RunStatus.RUNNING, null);
		CountDownLatch firstAppended = new CountDownLatch(1);
		CountDownLatch firstMayCommit = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Long> first = pool.submit(() -> {
				return researchTx.execute(s -> {
					long a = eventStore.append(run, "log", "{}", Instant.now());
					firstAppended.countDown();
					waitFor(firstMayCommit);
					return a;
				});
			});
			assertThat(firstAppended.await(30, TimeUnit.SECONDS)).isTrue();
			Future<Long> second = pool.submit(() -> researchTx.execute(s -> eventStore.append(run, "log", "{}", Instant.now())));
			// a negative check needs time: give the second writer a real chance to (wrongly) get through
			Thread.sleep(500);
			assertThat(second.isDone()).as("blocked while the first transaction is open").isFalse();
			firstMayCommit.countDown();
			first.get(30, TimeUnit.SECONDS);
			second.get(30, TimeUnit.SECONDS); // and it goes through once the first one has committed
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void waitFor(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
