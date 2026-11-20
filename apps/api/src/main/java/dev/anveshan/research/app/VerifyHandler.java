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
import dev.anveshan.byok.api.RequestTooLargeException;
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
 * <p>
 * Free models often answer for only part of a batch (reasoning models spend the output budget thinking). Claims that
 * got no verdict at all are asked once more in a smaller call (T-15-21); whatever is still missing is logged, so a
 * "No source" label never silently means "not checked".
 */
@Component
class VerifyHandler implements StepHandler {

	static final String PROMPT = "verifier";
	static final int PROMPT_VERSION = 2;
	static final int OTHER_PASSAGES = 3;
	/** Output budget per call; v1's 3,000 was used up by reasoning models before every pair was answered. */
	static final int MAX_TOKENS = 6000;
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
		JsonNode input = json.readTree(step.inputJson());
		List<UUID> ids = new ArrayList<>();
		for (JsonNode id : input.path("claimIds")) {
			ids.add(UUID.fromString(id.asString()));
		}
		// Find better sources (F-FR-5): passages come from this child run, the verdicts belong to the parent run
		String verdictRun = input.path("verdictRunId").asString("");
		UUID verdictRunId = verdictRun.isEmpty() ? step.runId() : UUID.fromString(verdictRun);
		List<Claim> batch = claims.claims(ids);
		if (batch.isEmpty()) {
			return new StepResult(Map.of("claims", 0, "verdicts", 0, "ignored", 0, "llmCalls", 0), List.of());
		}
		Map<UUID, List<Passage>> passagesOf = new LinkedHashMap<>();
		for (Claim c : batch) {
			passagesOf.put(c.id(), claims.verificationPassages(step.runId(), c, OTHER_PASSAGES));
		}

		List<NewEvent> events = new ArrayList<>();
		Judging first = judgeFitting(step, verdictRunId, batch, passagesOf, events);
		int llmCalls = first.calls();
		int saved = first.saved();
		int ignored = first.ignored();
		boolean unusable = first.unusable();
		List<Claim> skipped = batch.stream().filter(c -> !first.judged().contains(c.id())).toList();
		int retried = skipped.size();
		if (!skipped.isEmpty()) {
			events.add(log("INFO", step, "The AI skipped " + skipped.size() + " of " + batch.size()
					+ " claims — asking again for those."));
			Judging second = judgeFitting(step, verdictRunId, skipped, passagesOf, events);
			llmCalls += second.calls();
			saved += second.saved();
			ignored += second.ignored();
			unusable = unusable && second.unusable();
			skipped = skipped.stream().filter(c -> !second.judged().contains(c.id())).toList();
		}

		int passages = (int) passagesOf.values().stream().flatMap(List::stream).map(Passage::chunkId).distinct().count();
		events.add(0, log("INFO", step, "Checked " + batch.size() + " claims against " + passages + " passages ("
				+ saved + " verdicts)."));
		if (ignored > 0) {
			events.add(log("WARN", step, "Ignored " + ignored + " verdicts that referred to unknown claims or passages."));
		}
		if (unusable) {
			events.add(log("WARN", step, "The AI's answer wasn't usable — these claims stay unverified."));
		}
		else if (!skipped.isEmpty()) {
			events.add(log("WARN", step, skipped.size() + " claims couldn't be checked (the AI didn't answer for them). "
					+ "They show as \"No source found\" — use Find better sources on them later."));
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("claims", batch.size());
		output.put("passages", passages);
		output.put("verdicts", saved);
		output.put("ignored", ignored);
		output.put("retried", retried);
		output.put("unchecked", skipped.size());
		output.put("llmCalls", llmCalls);
		return new StepResult(output, events);
	}

	/** VERIFY call(s) over {@code batch}; which claims got at least one saved verdict, and how many calls it took. */
	record Judging(int saved, int ignored, boolean unusable, Set<UUID> judged, int calls) {
	}

	/**
	 * {@link #judge}, but a batch the models refuse as too large is checked in two halves, recursively (I-017): the
	 * same request would be refused forever, smaller ones fit. A single claim that is still too large stays unchecked.
	 */
	private Judging judgeFitting(StepContext step, UUID verdictRunId, List<Claim> batch, Map<UUID, List<Passage>> passagesOf,
			List<NewEvent> events) {
		try {
			return judge(step, verdictRunId, batch, passagesOf);
		}
		catch (RequestTooLargeException e) {
			if (batch.size() == 1) {
				events.add(log("WARN", step, "One claim and its passages were too large for your AI models; it stays unchecked."));
				return new Judging(0, 0, false, Set.of(), 1);
			}
			events.add(log("INFO", step, "The request was too large for your AI models — checking " + batch.size()
					+ " claims in two smaller parts."));
			int mid = batch.size() / 2;
			Judging a = judgeFitting(step, verdictRunId, batch.subList(0, mid), passagesOf, events);
			Judging b = judgeFitting(step, verdictRunId, batch.subList(mid, batch.size()), passagesOf, events);
			Set<UUID> judged = new LinkedHashSet<>(a.judged());
			judged.addAll(b.judged());
			return new Judging(a.saved() + b.saved(), a.ignored() + b.ignored(), a.unusable() && b.unusable(), judged,
					1 + a.calls() + b.calls());
		}
	}

	private Judging judge(StepContext step, UUID verdictRunId, List<Claim> batch, Map<UUID, List<Passage>> passagesOf) {
		// short aliases: fewer tokens, and the model can't point at anything we didn't list
		Map<String, Claim> claimByAlias = new LinkedHashMap<>();
		Map<String, Passage> passageByAlias = new LinkedHashMap<>();
		Map<UUID, String> aliasOfChunk = new LinkedHashMap<>();
		Map<String, Set<String>> pairs = new LinkedHashMap<>();
		for (Claim c : batch) {
			String ca = "C" + (claimByAlias.size() + 1);
			claimByAlias.put(ca, c);
			Set<String> allowed = new LinkedHashSet<>();
			for (Passage p : passagesOf.get(c.id())) {
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
		Set<UUID> judged = new LinkedHashSet<>();
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
			claims.saveVerdict(verdictRunId, claim.id(), passageByAlias.get(pa).chunkId(), verdict, reason);
			judged.add(claim.id());
			saved++;
		}
		return new Judging(saved, ignored, out == null, judged, 1);
	}

	private VerifyOutput call(StepContext step, Map<String, Claim> claimByAlias, Map<String, Passage> passageByAlias,
			Map<String, Set<String>> pairs) {
		StringBuilder claimList = new StringBuilder();
		claimByAlias.forEach((alias, c) -> claimList.append(alias).append(": ").append(Planner.data(c.text()))
				.append("\n   check against: ").append(String.join(", ", pairs.get(alias))).append('\n'));
		StringBuilder blocks = new StringBuilder();
		passageByAlias.forEach((alias, p) -> blocks.append("<source id=\"").append(alias).append("\">\n")
				.append(Planner.data(p.text())).append("\n</source>\n\n"));
		int pairCount = pairs.values().stream().mapToInt(Set::size).sum();
		Prompt prompt = prompts.load(PROMPT, PROMPT_VERSION, Map.of("claims", claimList.toString().strip(), "passages",
				blocks.toString().strip(), "pairs", String.valueOf(pairCount)), 0.0, MAX_TOKENS);
		try {
			return gateway.call(step.ownerId(), TaskSlot.VERIFY, prompt, VerifyOutput.class,
					CallContext.step(step.runId(), step.stepId())).value();
		}
		catch (NoModelForSlotException e) {
			throw new StepFailedException("No AI model is set for verifying claims. Choose one for \"Verify\" in "
					+ "Settings → AI, then retry this step.");
		}
		catch (RequestTooLargeException e) {
			throw e; // judgeFitting splits the batch
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
