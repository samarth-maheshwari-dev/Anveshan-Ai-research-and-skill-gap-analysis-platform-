package dev.anveshan.research.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.LabelCounts;

/** Stage 6: one LABEL step per run — every claim's trust label from its verdicts, by code only (L2). */
@Component
class LabelHandler implements StepHandler {

	private final ClaimLibrary claims;

	LabelHandler(ClaimLibrary claims) {
		this.claims = claims;
	}

	@Override
	public StepKind kind() {
		return StepKind.LABEL;
	}

	@Override
	public StepResult handle(StepContext step) {
		LabelCounts c = claims.labelRun(step.runId());
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("supported", c.supported());
		output.put("weak", c.weak());
		output.put("conflicting", c.conflicting());
		output.put("noSource", c.noSource());
		String msg = c.total() == 0 ? "No claims to label."
				: "Labelled " + c.total() + " claims: " + c.supported() + " supported, " + c.weak() + " weak, "
						+ c.conflicting() + " conflicting, " + c.noSource() + " with no source.";
		List<NewEvent> events = new java.util.ArrayList<>();
		events.add(new NewEvent("log", Map.of("stepId", step.stepId(), "level", "INFO", "message", msg)));
		claims.conflicts(step.runId()).stream().limit(10).forEach(k -> events.add(new NewEvent("conflict.found",
				Map.of("claimId", k.claimId(), "text", k.text(), "supporting", k.supportingSources().size(),
						"contradicting", k.contradictingSources().size()))));
		return new StepResult(output, events);
	}

}
