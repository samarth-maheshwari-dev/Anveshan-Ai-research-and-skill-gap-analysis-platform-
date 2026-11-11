package dev.anveshan.research.app;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import dev.anveshan.research.app.StepHandler.NewEvent;
import dev.anveshan.research.app.StepHandler.StepContext;
import dev.anveshan.research.app.StepHandler.StepResult;
import dev.anveshan.research.domain.ResearchEnums.StepKind;

/**
 * Fake step handlers + a fake stage graph, so the engine is proven before any real research logic (P09 goal).
 * Graph: PLAN (stage 0) → 2× SEARCH → 2× FETCH → 1× RELEVANCE → done. Each test can script what a step does.
 */
public class FakeSteps {

	/** What a fake step does on its {@code call}-th execution (1-based). Return null for the default result. */
	@FunctionalInterface
	public interface Behaviour {

		StepResult run(StepContext step, int call) throws InterruptedException;

	}

	public record Execution(UUID stepId, StepKind kind, int stage, int call, Instant started, Instant finished) {
	}

	public final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
	public final List<Execution> executions = new CopyOnWriteArrayList<>();
	private final Map<StepKind, Behaviour> behaviours = new ConcurrentHashMap<>();

	/** Runs whose next stage can't be worked out (the materializer throws) — a "broken" run for sweep tests. */
	public final Set<UUID> brokenRuns = ConcurrentHashMap.newKeySet();

	public void reset() {
		calls.clear();
		executions.clear();
		behaviours.clear();
		brokenRuns.clear();
	}

	public void on(StepKind kind, Behaviour b) {
		behaviours.put(kind, b);
	}

	public int callsOf(UUID stepId) {
		AtomicInteger n = calls.get(stepId);
		return n == null ? 0 : n.get();
	}

	StepResult handle(StepContext step) {
		int call = calls.computeIfAbsent(step.stepId(), k -> new AtomicInteger()).incrementAndGet();
		Instant started = Instant.now();
		try {
			Behaviour b = behaviours.get(step.kind());
			StepResult r = b == null ? null : b.run(step, call);
			return r != null ? r
					: new StepResult(Map.of("kind", step.kind().name(), "seq", step.seq()),
							List.of(new NewEvent("log", Map.of("stepId", step.stepId(), "message", "fake work done"))));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted");
		}
		finally {
			executions.add(new Execution(step.stepId(), step.kind(), step.stage(), call, started, Instant.now()));
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		/** The engine tests script PLAN themselves: the real Planner (it calls an LLM) steps aside. */
		@Bean
		static BeanDefinitionRegistryPostProcessor withoutRealPlanner() {
			return registry -> {
				if (registry.containsBeanDefinition("planHandler")) {
					registry.removeBeanDefinition("planHandler");
				}
			};
		}

		@Bean
		FakeSteps fakeSteps() {
			return new FakeSteps();
		}

		@Bean
		StepHandler fakePlan(FakeSteps f) {
			return handler(StepKind.PLAN, f);
		}

		@Bean
		StepHandler fakeSearch(FakeSteps f) {
			return handler(StepKind.SEARCH, f);
		}

		@Bean
		StepHandler fakeFetch(FakeSteps f) {
			return handler(StepKind.FETCH, f);
		}

		@Bean
		StepHandler fakeRelevance(FakeSteps f) {
			return handler(StepKind.RELEVANCE, f);
		}

		/** Stage 1: 2× SEARCH, stage 2: 2× FETCH, stage 3: 1× RELEVANCE, then the run completes. */
		@Bean
		@Primary
		StageMaterializer fakeStages(FakeSteps f) {
			return (run, closed) -> f.brokenRuns.contains(run.id()) ? broken() : switch (closed) {
				case 0 -> List.of(step(StepKind.SEARCH, 1), step(StepKind.SEARCH, 2));
				case 1 -> List.of(step(StepKind.FETCH, 1), step(StepKind.FETCH, 2));
				case 2 -> List.of(step(StepKind.RELEVANCE, 1));
				default -> List.of();
			};
		}

		private static List<StageMaterializer.NewStep> broken() {
			throw new IllegalStateException("broken run");
		}

		private static StageMaterializer.NewStep step(StepKind kind, int n) {
			return new StageMaterializer.NewStep(kind, null, Map.of("n", n));
		}

		private static StepHandler handler(StepKind kind, FakeSteps f) {
			return new StepHandler() {
				@Override
				public StepKind kind() {
					return kind;
				}

				@Override
				public StepResult handle(StepContext step) {
					return f.handle(step);
				}
			};
		}

	}

}
