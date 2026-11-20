package dev.anveshan.research.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.report.api.Reports;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.LabelCounts;

/** Stage 6: one LABEL step per run — every claim's trust label from its verdicts, by code only (L2). */
@Component
class LabelHandler implements StepHandler {

	private final ClaimLibrary claims;
	private final Reports reports;
	private final tools.jackson.databind.json.JsonMapper json;

	LabelHandler(ClaimLibrary claims, Reports reports, tools.jackson.databind.json.JsonMapper json) {
		this.claims = claims;
		this.reports = reports;
		this.json = json;
	}

	@Override
	public StepKind kind() {
		return StepKind.LABEL;
	}

	@Override
	public StepResult handle(StepContext step) {
		var input = json.readTree(step.inputJson());
		if (input.has("refreshRunId")) {
			return refresh(step, java.util.UUID.fromString(input.path("refreshRunId").asString()),
					java.util.UUID.fromString(input.path("claimId").asString()),
					java.util.UUID.fromString(input.path("reportId").asString()));
		}
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

	/** Find better sources: the original claim relabelled in its own run; the report's citations follow (F-FR-5). */
	private StepResult refresh(StepContext step, java.util.UUID parentRun, java.util.UUID claimId,
			java.util.UUID reportId) {
		String before = claims.reportClaims(parentRun, List.of(claimId)).stream().findFirst()
				.map(ClaimLibrary.ReportClaim::label).orElse(null);
		String after = claims.labelClaim(parentRun, claimId);
		if (after != null) {
			reports.refreshLabels(reportId);
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("claimId", claimId.toString());
		if (before != null) {
			output.put("before", before);
		}
		if (after != null) {
			output.put("after", after);
		}
		String msg = after == null ? "The claim is no longer part of the report."
				: java.util.Objects.equals(before, after) ? "No stronger evidence found: the claim stays " + after + "."
						: "The claim is now " + after + " (was " + before + ").";
		Map<String, Object> updated = new LinkedHashMap<>();
		updated.put("claimId", claimId.toString());
		updated.put("reportId", reportId.toString());
		updated.putAll(output);
		return new StepResult(output, List.of(
				new NewEvent("log", Map.of("stepId", step.stepId(), "level", "INFO", "message", msg)),
				new NewEvent("claim.updated", updated)));
	}

}
