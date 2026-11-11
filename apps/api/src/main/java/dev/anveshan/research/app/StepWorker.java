package dev.anveshan.research.app;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.AllRoutesExhaustedException;
import dev.anveshan.research.app.RunEngine.Claimed;
import dev.anveshan.research.app.StepHandler.StepContext;
import dev.anveshan.research.app.StepHandler.StepResult;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.StepStore.StepRow;

/**
 * Runs one step (TECHSPEC §12.2): claim atomically → do the work <b>outside</b> any DB transaction → save the
 * checkpoint and events in one short transaction → close the stage if it's done. Public because JobRunr invokes it.
 */
@Component
public class StepWorker {

	private static final Logger log = LoggerFactory.getLogger(StepWorker.class);

	private final RunEngine engine;
	private final Map<StepKind, StepHandler> handlers = new EnumMap<>(StepKind.class);

	StepWorker(RunEngine engine, List<StepHandler> handlers) {
		this.engine = engine;
		for (StepHandler h : handlers) {
			if (this.handlers.put(h.kind(), h) != null) {
				throw new IllegalStateException("Two step handlers for " + h.kind());
			}
		}
	}

	@Job(name = "Research step %0", retries = 0) // retries follow our own policy (RunEngine.onFailure)
	public void execute(UUID stepId) {
		Optional<Claimed> claimed = engine.claim(stepId);
		if (claimed.isEmpty()) {
			return; // someone else has it, it's already DONE, or the run is paused/cancelled
		}
		StepRow step = claimed.get().step();
		MDC.put("runId", step.runId().toString());
		MDC.put("stepId", stepId.toString());
		boolean done = false;
		try {
			StepHandler handler = handlers.get(step.kind());
			if (handler == null) {
				throw new StepFailedException("This research step isn't available yet.");
			}
			StepResult result = handler.handle(new StepContext(step.runId(), claimed.get().run().ownerId(), step.id(),
					step.kind(), step.stage(), step.seq(), step.subQuestionId(), step.inputJson(), step.attempts()));
			done = engine.onDone(step, result);
		}
		catch (AllRoutesExhaustedException e) {
			engine.onAiLimit(step, e.resumeAt());
		}
		catch (StepFailedException e) {
			engine.onFailure(step, e.getMessage(), e.retryable());
		}
		catch (RuntimeException e) {
			// the message may come from a provider or a library: never log or store it, only the type
			log.warn("Step {} ({}) attempt {} failed: {}", stepId, step.kind(), step.attempts(),
					e.getClass().getSimpleName());
			engine.onFailure(step, "The step failed unexpectedly (" + e.getClass().getSimpleName() + ").", true);
		}
		finally {
			MDC.remove("runId");
			MDC.remove("stepId");
		}
		if (done) {
			// outside the handler's try: a problem here must not mark the (already DONE) step as failed. If it
			// throws, or the process dies right here, RunSweeper settles the idle run on its next pass.
			try {
				engine.advance(step.runId());
			}
			catch (RuntimeException e) {
				log.warn("Advancing run {} after step {} failed: {}", step.runId(), stepId, e.getClass().getSimpleName());
			}
		}
	}

}
