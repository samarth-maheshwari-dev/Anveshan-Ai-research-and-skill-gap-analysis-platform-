package dev.anveshan.research.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.byok.api.AllRoutesExhaustedException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.app.FakeSteps.Execution;
import dev.anveshan.research.domain.ResearchEnums.RunStatus;
import dev.anveshan.research.domain.ResearchEnums.StepKind;

/** The run engine with fake steps (TECHSPEC §12.1–12.2, E-FR-1/2/4, AC-E1/E2). */
class ResearchEngineIT extends ResearchITBase {

	@Test
	@DisplayName("T-09-03 run with PLAN + 3 fake stages → every step DONE once, stages in order, run COMPLETED at 100 %")
	void stagesRunInOrder() throws Exception {
		UUID planned = startRun(alice);
		awaitStatus(planned, RunStatus.AWAITING_APPROVAL);
		assertThat(progress(planned)).as("planning isn't research progress").isZero();
		engine.approve(planned);
		UUID run = planned;
		awaitStatus(run, RunStatus.COMPLETED);
		List<Double> progress = jdbc.sql("""
				SELECT (payload->>'progress')::numeric FROM run_event WHERE run_id = :r AND type = 'run.progress' ORDER BY id""")
				.param("r", run).query(Double.class).list();
		assertThat(progress).as("never goes down").isSortedAccordingTo(Comparator.naturalOrder());
		assertThat(progress.getLast()).as("3 of 8 research stages done (RunProgress), not 100 before COMPLETED")
				.isEqualTo(37.5);

		List<Map<String, Object>> rows = jdbc.sql("SELECT kind, stage, status FROM research_step WHERE run_id = :r ORDER BY seq")
				.param("r", run).query().listOfRows();
		assertThat(rows).extracting(r -> r.get("kind") + "@" + r.get("stage") + ":" + r.get("status"))
				.containsExactly("PLAN@0:DONE", "SEARCH@1:DONE", "SEARCH@1:DONE", "FETCH@2:DONE", "FETCH@2:DONE",
						"RELEVANCE@3:DONE");

		// every step ran exactly once, and no step of stage n+1 started before every step of stage n finished
		for (UUID step : stepsOf(run)) {
			assertThat(fake.callsOf(step)).as("each step of this run ran once").isEqualTo(1);
		}
		Map<Integer, List<Execution>> byStage = fake.executions.stream().filter(e -> stepsOf(run).contains(e.stepId()))
				.collect(Collectors.groupingBy(Execution::stage));
		for (int stage = 1; stage <= 3; stage++) {
			var prevFinished = byStage.get(stage - 1).stream().map(Execution::finished).max(Comparator.naturalOrder())
					.orElseThrow();
			var nextStarted = byStage.get(stage).stream().map(Execution::started).min(Comparator.naturalOrder())
					.orElseThrow();
			assertThat(nextStarted).as("stage " + stage + " starts after stage " + (stage - 1)).isAfterOrEqualTo(prevFinished);
		}

		var r = jdbc.sql("SELECT progress, completed_at FROM research_run WHERE id = :r").param("r", run).query()
				.singleRow();
		assertThat(((Number) r.get("progress")).doubleValue()).isEqualTo(100.0);
		assertThat(r.get("completed_at")).isNotNull();
		assertThat(events(run, "step.completed", null)).isEqualTo(6);
		assertThat(events(run, "run.status", null)).as("PLANNING, AWAITING_APPROVAL, RUNNING, COMPLETED").isEqualTo(4);
	}

	@Test
	@DisplayName("T-09-02 two workers claim the same step at once → exactly one runs it")
	void onlyOneClaimWins() throws Exception {
		UUID run = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO research_run (id, owner_id, topic, config, status) VALUES (:id, :o, 'Two workers, one step', '{}', 'RUNNING')""")
				.param("id", run).param("o", alice).update();
		UUID step = jdbc.sql("""
				INSERT INTO research_step (run_id, seq, stage, kind, input, idempotency_key)
				VALUES (:r, 0, 3, 'RELEVANCE', '{}', :r || ':RELEVANCE:0') RETURNING id""")
				.param("r", run).query(UUID.class).single();
		fake.on(StepKind.RELEVANCE, (s, call) -> {
			Thread.sleep(300); // keep the winner busy while the loser tries
			return null;
		});

		CyclicBarrier together = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> both = List.of(pool.submit(() -> race(together, step)), pool.submit(() -> race(together, step)));
			for (Future<?> f : both) {
				f.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(fake.callsOf(step)).isEqualTo(1);
		assertThat(stepStatus(step)).isEqualTo("DONE");
		assertThat(jdbc.sql("SELECT attempts FROM research_step WHERE id = :s").param("s", step).query(Integer.class)
				.single()).isEqualTo(1);
		assertThat(events(run, "step.started", step)).isEqualTo(1);
	}

	private Object race(CyclicBarrier together, UUID step) throws Exception {
		together.await(10, TimeUnit.SECONDS);
		worker.execute(step);
		return null;
	}

	@Test
	@DisplayName("T-09-04 worker dies mid-stage → recovery re-runs only that step, no DONE step runs twice, the late "
			+ "'zombie' can't overwrite it, run completes (AC-E1)")
	void crashedStepIsRecovered() throws Exception {
		CountDownLatch zombieMayFinish = new CountDownLatch(1);
		CountDownLatch zombieFinished = new CountDownLatch(1);
		AtomicReference<UUID> crashed = new AtomicReference<>();
		fake.on(StepKind.FETCH, (s, call) -> {
			if (crashed.compareAndSet(null, s.stepId())) { // the first FETCH "hangs" — its worker is considered dead
				try {
					zombieMayFinish.await(60, TimeUnit.SECONDS);
					return new StepHandler.StepResult(Map.of("from", "zombie"), List.of());
				}
				finally {
					zombieFinished.countDown();
				}
			}
			return new StepHandler.StepResult(Map.of("from", "attempt " + call), List.of());
		});
		UUID run = startApprovedRun(alice);

		// wait until the hung FETCH is RUNNING and everything else in its stage is DONE
		await().atMost(WAIT).untilAsserted(() -> {
			assertThat(crashed.get()).isNotNull();
			assertThat(stepStatus(crashed.get())).isEqualTo("RUNNING");
			List<UUID> fetches = steps(run, "FETCH");
			assertThat(fetches).hasSize(2);
			fetches.stream().filter(id -> !id.equals(crashed.get()))
					.forEach(id -> assertThat(stepStatus(id)).isEqualTo("DONE"));
		});
		List<UUID> doneBeforeCrash = jdbc.sql("SELECT id FROM research_step WHERE run_id = :r AND status = 'DONE'")
				.param("r", run).query(UUID.class).list();

		// "restart": the stale-step sweep sees a RUNNING step older than stale-after and re-queues it
		assertThat(sweeper.recoverStale(Instant.now().plus(Duration.ofMinutes(1)))).isGreaterThanOrEqualTo(1);
		awaitStatus(run, RunStatus.COMPLETED);

		// the original worker wakes up late: its attempt is no longer the step's attempt, so nothing changes
		zombieMayFinish.countDown();
		assertThat(zombieFinished.await(30, TimeUnit.SECONDS)).isTrue();
		await().pollDelay(Duration.ofMillis(500)).atMost(WAIT).untilAsserted(() -> {
			assertThat(jdbc.sql("SELECT output->>'from' FROM research_step WHERE id = :s").param("s", crashed.get())
					.query(String.class).single()).isEqualTo("attempt 2");
			assertThat(events(run, "step.completed", crashed.get())).isEqualTo(1);
		});
		for (UUID done : doneBeforeCrash) {
			assertThat(fake.callsOf(done)).as("a DONE step never runs again").isEqualTo(1);
		}
		assertThat(fake.callsOf(crashed.get())).isEqualTo(2);
		assertThat(status(run)).isEqualTo(RunStatus.COMPLETED);
	}

	@Test
	@DisplayName("T-09-05 a step hits AllRoutesExhausted → run PAUSED(AI_LIMIT_REACHED) with resume_at within 5 s; the "
			+ "sweep auto-resumes it and it completes (AC-E2)")
	void aiLimitPausesAndAutoResumes() throws Exception {
		AtomicReference<Instant> thrownAt = new AtomicReference<>();
		AtomicReference<Instant> resetAt = new AtomicReference<>();
		fake.on(StepKind.SEARCH, (s, call) -> {
			if (call == 1 && resetAt.compareAndSet(null, Instant.now().plusSeconds(3))) {
				thrownAt.set(Instant.now());
				throw new AllRoutesExhaustedException(TaskSlot.EXTRACT, resetAt.get()); // provider resets in 3 s
			}
			return null;
		});
		UUID run = startApprovedRun(alice);

		await().atMost(WAIT).pollInterval(Duration.ofMillis(50))
				.untilAsserted(() -> assertThat(status(run)).isEqualTo(RunStatus.PAUSED));
		var r = jdbc.sql("SELECT pause_reason, resume_at FROM research_run WHERE id = :r").param("r", run).query()
				.singleRow();
		assertThat(r.get("pause_reason")).isEqualTo("AI_LIMIT_REACHED");
		assertThat(((java.sql.Timestamp) r.get("resume_at")).toInstant()).isCloseTo(resetAt.get(),
				within(1, ChronoUnit.MILLIS));
		var paused = jdbc.sql("""
				SELECT created_at, payload->>'resumeAt' AS resume_at FROM run_event WHERE run_id = :r AND type = 'run.status'
				   AND payload->>'status' = 'PAUSED' AND payload->>'pauseReason' = 'AI_LIMIT_REACHED'""")
				.param("r", run).query().singleRow();
		assertThat(Duration.between(thrownAt.get(), ((java.sql.Timestamp) paused.get("created_at")).toInstant()))
				.as("shown as paused within 5 s (AC-E2)").isLessThan(Duration.ofSeconds(5));
		assertThat(Instant.parse((String) paused.get("resume_at"))).isEqualTo(resetAt.get());
		// the step that hit the limit is back in the queue without having used up an attempt
		assertThat(jdbc.sql("SELECT count(*) FROM research_step WHERE run_id = :r AND kind = 'SEARCH' AND attempts > 1")
				.param("r", run).query(Long.class).single()).isZero();

		awaitStatus(run, RunStatus.COMPLETED); // the sweep (every 1 s in tests) resumed it after resetAt
		var resumedEvent = jdbc.sql("""
				SELECT created_at FROM run_event WHERE run_id = :r AND type = 'run.status' AND payload->>'status' = 'RUNNING'
				 ORDER BY id DESC LIMIT 1""").param("r", run).query(java.sql.Timestamp.class).single().toInstant();
		assertThat(resumedEvent).as("not resumed before the provider's reset time").isAfterOrEqualTo(resetAt.get());
		assertThat(jdbc.sql("SELECT pause_reason FROM research_run WHERE id = :r").param("r", run).query(String.class)
				.optional()).isEmpty();
	}

	@Test
	@DisplayName("T-09-06 pause during stage 2 → the running step finishes, a queued one is released (not run), nothing "
			+ "advances; resume continues from the next step and completes (E-FR-2)")
	void pauseAndResume() throws Exception {
		CountDownLatch releaseRunning = new CountDownLatch(1);
		CountDownLatch pausePressed = new CountDownLatch(1);
		AtomicReference<UUID> running = new AtomicReference<>();
		AtomicReference<UUID> queued = new AtomicReference<>();
		fake.on(StepKind.FETCH, (s, call) -> {
			if (running.compareAndSet(null, s.stepId())) {
				releaseRunning.await(60, TimeUnit.SECONDS); // still working when the user presses Pause
				return null;
			}
			if (call == 1) {
				queued.set(s.stepId());
				pausePressed.await(60, TimeUnit.SECONDS); // fails only once Pause was pressed: its retry is then
				throw new IllegalStateException("flaky"); // certainly queued during the pause (no timing race)
			}
			return null;
		});
		UUID run = startApprovedRun(alice);
		await().atMost(WAIT).untilAsserted(() -> {
			assertThat(running.get()).isNotNull();
			assertThat(queued.get()).isNotNull();
		});

		var pause = perform(alice, post("/api/v1/runs/" + run + "/pause"));
		assertThat(pause.getResponse().getStatus()).as(body(pause)).isEqualTo(202);
		assertThat(status(run)).isEqualTo(RunStatus.PAUSED);
		pausePressed.countDown();
		await().atMost(WAIT).untilAsserted(() -> assertThat(stepStatus(queued.get())).isEqualTo("PENDING"));

		// the queued step's job fires during the pause (run here directly, so it certainly happens while paused):
		// the step is released at once — its handler is not called, it stays PENDING without spending an attempt
		worker.execute(queued.get());
		assertThat(fake.callsOf(queued.get())).isEqualTo(1);
		assertThat(stepStatus(queued.get())).isEqualTo("PENDING");
		assertThat(events(run, "step.started", queued.get())).isEqualTo(1);
		releaseRunning.countDown();
		await().atMost(WAIT).untilAsserted(() -> assertThat(stepStatus(running.get())).isEqualTo("DONE"));
		assertThat(stepStatus(queued.get())).isEqualTo("PENDING");
		assertThat(steps(run, "RELEVANCE")).as("a paused run doesn't open the next stage").isEmpty();
		assertThat(status(run)).isEqualTo(RunStatus.PAUSED);

		var resume = perform(alice, post("/api/v1/runs/" + run + "/resume"));
		assertThat(resume.getResponse().getStatus()).as(body(resume)).isEqualTo(202);
		awaitStatus(run, RunStatus.COMPLETED);
		assertThat(fake.callsOf(running.get())).as("the step that finished during the pause never re-runs").isEqualTo(1);
		assertThat(fake.callsOf(queued.get())).isEqualTo(2);

		// resume/pause only from the right status → 409 RUN_INVALID_STATE
		var again = perform(alice, post("/api/v1/runs/" + run + "/resume"));
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(jsonOf(again).path("code").asString()).isEqualTo("RUN_INVALID_STATE");
		assertThat(perform(alice, post("/api/v1/runs/" + run + "/pause")).getResponse().getStatus()).isEqualTo(409);
	}

	@Test
	@DisplayName("T-09-07 a step fails 3× → step FAILED (3 attempts, safe error), run FAILED; retry → RUNNING → COMPLETED "
			+ "(E-FR-4)")
	void failedStepAndRetry() throws Exception {
		AtomicBoolean broken = new AtomicBoolean(true);
		fake.on(StepKind.RELEVANCE, (s, call) -> {
			if (broken.get()) {
				throw new IllegalStateException("secret-looking provider text sk-123 must never be stored");
			}
			return null;
		});
		UUID run = startApprovedRun(alice);
		awaitStatus(run, RunStatus.FAILED);

		UUID step = steps(run, "RELEVANCE").get(0);
		var s = jdbc.sql("SELECT status, attempts, last_error FROM research_step WHERE id = :s").param("s", step).query()
				.singleRow();
		assertThat(s.get("status")).isEqualTo("FAILED");
		assertThat(((Number) s.get("attempts")).intValue()).isEqualTo(3);
		assertThat((String) s.get("last_error")).contains("IllegalStateException").doesNotContain("sk-123");
		assertThat(fake.callsOf(step)).isEqualTo(3);
		assertThat(jdbc.sql("""
				SELECT payload->>'willRetry' FROM run_event WHERE run_id = :r AND type = 'step.failed' ORDER BY id""")
				.param("r", run).query(String.class).list()).containsExactly("true", "true", "false");

		// retrying a step that isn't FAILED is refused
		UUID doneStep = steps(run, "SEARCH").get(0);
		var wrong = perform(alice, post("/api/v1/runs/" + run + "/steps/" + doneStep + "/retry"));
		assertThat(wrong.getResponse().getStatus()).isEqualTo(409);

		broken.set(false);
		var retry = perform(alice, post("/api/v1/runs/" + run + "/steps/" + step + "/retry"));
		assertThat(retry.getResponse().getStatus()).as(body(retry)).isEqualTo(202);
		awaitStatus(run, RunStatus.COMPLETED);
		assertThat(stepStatus(step)).isEqualTo("DONE");
		assertThat(fake.callsOf(step)).isEqualTo(4);
	}

	double progress(UUID run) {
		return jdbc.sql("SELECT progress FROM research_run WHERE id = :r").param("r", run).query(Double.class).single();
	}

	List<UUID> stepsOf(UUID run) {
		return jdbc.sql("SELECT id FROM research_step WHERE run_id = :r").param("r", run).query(UUID.class).list();
	}

}
