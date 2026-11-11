package dev.anveshan.research.app;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.ProviderUnavailableException;
import dev.anveshan.research.app.Planner.Proposal;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.RunStore;
import dev.anveshan.research.infra.SubQuestionStore;

/**
 * Stage 0: asks the {@link Planner} for the run's plan and saves it. Safe to run twice: the plan is replaced as a
 * whole. Closing stage 0 moves the run to AWAITING_APPROVAL (RunEngine). An AI limit (429) is not caught here —
 * the worker pauses the run until the limit resets.
 */
@Component
class PlanHandler implements StepHandler {

	private final Planner planner;
	private final RunStore runs;
	private final SubQuestionStore subQuestions;
	private final TransactionTemplate tx;

	PlanHandler(Planner planner, RunStore runs, SubQuestionStore subQuestions, TransactionTemplate researchTx) {
		this.planner = planner;
		this.runs = runs;
		this.subQuestions = subQuestions;
		this.tx = researchTx;
	}

	@Override
	public StepKind kind() {
		return StepKind.PLAN;
	}

	@Override
	public StepResult handle(StepContext step) {
		RunStore.RunRow run = runs.find(step.runId())
				.orElseThrow(() -> new StepFailedException("This research no longer exists."));
		Proposal proposal;
		try {
			proposal = planner.propose(run, null, CallContext.step(step.runId(), step.stepId()));
		}
		catch (NoModelForSlotException e) {
			throw new StepFailedException("No AI model is set for planning. Choose one in Settings → AI, then start again.");
		}
		catch (ProviderUnavailableException e) {
			// e.g. Gemini 503 "model overloaded": usually gone in a minute or two → retried with backoff first
			throw new StepFailedException("Your AI provider didn't answer — it was busy or down (this happens with free "
					+ "models at peak times). Anveshan tried several times. Start again in a few minutes, or add a second "
					+ "model for planning in Settings → AI so Anveshan can switch to it.", true);
		}
		catch (InvalidLlmOutputException e) {
			throw new StepFailedException(
					"The AI didn't return a usable plan. Start the research again, or pick another model for planning.");
		}
		catch (IllegalArgumentException e) {
			throw new StepFailedException("The AI's plan wasn't usable (" + e.getMessage()
					+ ") Start the research again, or pick another model for planning.");
		}
		List<dev.anveshan.research.domain.PlanRules.PlannedQuestion> plan = proposal.questions();
		tx.executeWithoutResult(s -> subQuestions.replace(step.runId(), plan, null));
		return new StepResult(Map.of("subQuestions", plan.size(), "model", proposal.model()),
				List.of(new NewEvent("plan.ready", Map.of("subQuestions", plan.size()))));
	}

}
