package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.CallContext;
import dev.anveshan.byok.api.InvalidLlmOutputException;
import dev.anveshan.byok.api.LlmGateway;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.Prompt;
import dev.anveshan.byok.api.ProviderUnavailableException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.infra.PromptFiles;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.Claim;
import dev.anveshan.sources.api.ClaimLibrary.Passage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 5: one VERIFY step per 8–10 claims (TECHSPEC §12.7, TD-3). Each claim is judged against its own passage and
 * the 3 most similar passages of other sources in the run. Only listed (claim, passage) pairs are saved: a verdict
 * for an unknown claim or passage is ignored and logged (T-13-08).
 */
@Component
class VerifyHandler implements StepHandler {

	static final String PROMPT = "verifier";
	static final int PROMPT_VERSION = 1;
	static final int OTHER_PASSAGES = 3;
	static final Set<String> VERDICTS = Set.of("SUPPORTS", "CONTRADICTS", "INSUFFICIENT", "PARTIAL");

	record VerifyOutput(@NotNull @Size(max = 200) List<@Valid VerdictItem> verdicts) {
	}

	record VerdictItem(@NotBlank @Size(max = 20) String claimId, @NotBlank @Size(max = 20) String chunkId,
			@NotBlank @Size(max = 20) String verdict, @Size(max = 600) String reason) {
	}

	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final ClaimLibrary claims;
	private final JsonMapper json;

	VerifyHandler(LlmGateway gateway, PromptFiles prompts, ClaimLibrary claims, JsonMapper json) {
		this.gateway = gateway;
		this.prompts = prompts;
		this.claims = claims;
		this.json = json;
	}

	@Override
	public StepKind kind() {
		return StepKind.VERIFY;
	}

	@Override
	public StepResult handle(StepContext step) {
		List<UUID> ids = new ArrayList<>();
		for (JsonNode id : json.readTree(step.inputJson()).path("claimIds")) {
			ids.add(UUID.fromString(id.asString()));
		}
		List<Claim> batch = claims.claims(ids);
		if (batch.isEmpty()) {
			return new StepResult(Map.of("claims", 0, "verdicts", 0, "ignored", 0, "llmCalls", 0), List.of());
		}

		// short aliases: fewer tokens, and the model can't point at anything we didn't list
		Map<String, Claim> claimByAlias = new LinkedHashMap<>();
		Map<String, Passage> passageByAlias = new LinkedHashMap<>();
		Map<UUID, String> aliasOfChunk = new LinkedHashMap<>();
		Map<String, Set<String>> pairs = new LinkedHashMap<>();
		for (Claim c : batch) {
			String ca = "C" + (claimByAlias.size() + 1);
			claimByAlias.put(ca, c);
			Set<String> allowed = new LinkedHashSet<>();
			for (Passage p : claims.verificationPassages(step.runId(), c, OTHER_PASSAGES)) {
				String pa = aliasOfChunk.computeIfAbsent(p.chunkId(), k -> {
					String a = "P" + (passageByAlias.size() + 1);
					passageByAlias.put(a, p);
					return a;
				});
				allowed.add(pa);
			}
			pairs.put(ca, allowed);
		}

		VerifyOutput out = call(step, claimByAlias, passageByAlias, pairs);
		int saved = 0;
		int ignored = 0;
		Set<String> seen = new LinkedHashSet<>();
		for (VerdictItem v : out == null ? List.<VerdictItem>of() : out.verdicts()) {
			String ca = v.claimId().strip();
			String pa = v.chunkId().strip();
			String verdict = v.verdict().strip().toUpperCase(java.util.Locale.ROOT);
			Claim claim = claimByAlias.get(ca);
			if (claim == null || !pairs.get(ca).contains(pa) || !VERDICTS.contains(verdict) || !seen.add(ca + "|" + pa)) {
				ignored++;
				continue;
			}
			String reason = v.reason() == null ? null : v.reason().strip();
			if (reason != null && reason.length() > 200) {
				reason = reason.substring(0, 199) + "…";
			}
			claims.saveVerdict(step.runId(), claim.id(), passageByAlias.get(pa).chunkId(), verdict, reason);
			saved++;
		}

		List<NewEvent> events = new ArrayList<>();
		events.add(log("INFO", step, "Checked " + batch.size() + " claims against " + passageByAlias.size()
				+ " passages (" + saved + " verdicts)."));
		if (ignored > 0) {
			events.add(log("WARN", step, "Ignored " + ignored + " verdicts that referred to unknown claims or passages."));
		}
		if (out == null) {
			events.add(log("WARN", step, "The AI's answer wasn't usable — these claims stay unverified."));
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("claims", batch.size());
		output.put("passages", passageByAlias.size());
		output.put("verdicts", saved);
		output.put("ignored", ignored);
		output.put("llmCalls", 1);
		return new StepResult(output, events);
	}

	private VerifyOutput call(StepContext step, Map<String, Claim> claimByAlias, Map<String, Passage> passageByAlias,
			Map<String, Set<String>> pairs) {
		StringBuilder claimList = new StringBuilder();
		claimByAlias.forEach((alias, c) -> claimList.append(alias).append(": ").append(Planner.data(c.text()))
				.append("\n   check against: ").append(String.join(", ", pairs.get(alias))).append('\n'));
		StringBuilder blocks = new StringBuilder();
		passageByAlias.forEach((alias, p) -> blocks.append("<source id=\"").append(alias).append("\">\n")
				.append(Planner.data(p.text())).append("\n</source>\n\n"));
		Prompt prompt = prompts.load(PROMPT, PROMPT_VERSION,
				Map.of("claims", claimList.toString().strip(), "passages", blocks.toString().strip()), 0.0, 3000);
		try {
			return gateway.call(step.ownerId(), TaskSlot.VERIFY, prompt, VerifyOutput.class,
					CallContext.step(step.runId(), step.stepId())).value();
		}
		catch (NoModelForSlotException e) {
			throw new StepFailedException("No AI model is set for verifying claims. Choose one for \"Verify\" in "
					+ "Settings → AI, then retry this step.");
		}
		catch (ProviderUnavailableException e) {
			throw new StepFailedException("Your AI provider didn't answer — it was busy or down. Anveshan will try again.",
					true);
		}
		catch (InvalidLlmOutputException e) {
			return null; // these claims get no verdicts → "No source" unless another batch judged them
		}
	}

	private static NewEvent log(String level, StepContext step, String message) {
		return new NewEvent("log", Map.of("stepId", step.stepId(), "level", level, "message", message));
	}

}
