package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.HashMap;
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
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.research.infra.SubQuestionStore.SubQuestionRow;
import dev.anveshan.sources.api.ClaimLibrary;
import dev.anveshan.sources.api.ClaimLibrary.AnchorOutcome;
import dev.anveshan.sources.api.ClaimLibrary.Anchored;
import dev.anveshan.sources.api.ClaimLibrary.Claim;
import dev.anveshan.sources.api.ClaimLibrary.Passage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 4: one EXTRACT step per batch of 2–3 sources × top chunks (TECHSPEC §12.1, TD-3). Chunks that already have
 * claims for this prompt version reuse them — no LLM call (TD-4). The rest go to one EXTRACT call; each claim's
 * quote is located in its chunk by code (L1) and must be what the claim is about, otherwise it is dropped.
 */
@Component
class ExtractHandler implements StepHandler {

	static final String PROMPT = "extractor";
	static final int PROMPT_VERSION = 1;
	static final String VERSION_TAG = PROMPT + "/v" + PROMPT_VERSION;
	static final int MAX_CLAIMS_PER_CHUNK = 5;

	/** What the model must return; ids are the short passage aliases (P1, P2 …) from the prompt. */
	record ExtractOutput(@NotNull @Size(max = 60) List<@Valid ExtractedClaim> claims) {
	}

	record ExtractedClaim(@NotBlank @Size(max = 20) String chunkId, @NotBlank @Size(max = 600) String text,
			@NotBlank @Size(max = 1000) String quote) {
	}

	private record Item(UUID chunkId, UUID subQuestionId) {
	}

	private final LlmGateway gateway;
	private final PromptFiles prompts;
	private final ClaimLibrary claims;
	private final SubQuestionStore subQuestions;
	private final JsonMapper json;

	ExtractHandler(LlmGateway gateway, PromptFiles prompts, ClaimLibrary claims, SubQuestionStore subQuestions,
			JsonMapper json) {
		this.gateway = gateway;
		this.prompts = prompts;
		this.claims = claims;
		this.subQuestions = subQuestions;
		this.json = json;
	}

	@Override
	public StepKind kind() {
		return StepKind.EXTRACT;
	}

	@Override
	public StepResult handle(StepContext step) {
		List<Item> items = new ArrayList<>();
		for (JsonNode i : json.readTree(step.inputJson()).path("items")) {
			String sq = i.path("subQuestionId").asString("");
			items.add(new Item(UUID.fromString(i.path("chunkId").asString()), sq.isEmpty() ? null : UUID.fromString(sq)));
		}
		Map<UUID, Passage> passages = claims.passages(items.stream().map(Item::chunkId).toList());

		int reused = 0;
		List<Item> fresh = new ArrayList<>();
		for (Item item : items) {
			if (!passages.containsKey(item.chunkId())) {
				continue; // the source was deleted meanwhile
			}
			List<Claim> cached = claims.cached(item.chunkId(), VERSION_TAG);
			if (cached.isEmpty()) {
				fresh.add(item);
				continue;
			}
			for (Claim c : cached) {
				claims.useInRun(step.runId(), c.id(), item.subQuestionId());
			}
			reused += cached.size();
		}

		int saved = 0;
		int notInSource = 0;
		int notGrounded = 0;
		int ignored = 0;
		int calls = 0;
		List<NewEvent> events = new ArrayList<>();
		if (!fresh.isEmpty()) {
			Map<String, Item> byAlias = new LinkedHashMap<>();
			for (Item item : fresh) {
				byAlias.put("P" + (byAlias.size() + 1), item);
			}
			ExtractOutput out = call(step, byAlias, passages);
			calls = 1;
			Map<UUID, Integer> perChunk = new HashMap<>();
			for (ExtractedClaim c : out == null ? List.<ExtractedClaim>of() : out.claims()) {
				Item item = byAlias.get(c.chunkId().strip());
				if (item == null || perChunk.merge(item.chunkId(), 1, Integer::sum) > MAX_CLAIMS_PER_CHUNK) {
					ignored++;
					continue;
				}
				Anchored a = claims.anchor(item.chunkId(), c.text(), c.quote(), VERSION_TAG);
				switch (a.outcome()) {
					case SAVED, REUSED -> {
						claims.useInRun(step.runId(), a.claim().id(), item.subQuestionId());
						saved++;
					}
					case NOT_IN_SOURCE -> notInSource++;
					case NOT_GROUNDED -> notGrounded++;
				}
			}
			if (out == null) {
				events.add(log("WARN", step, "The AI's answer for " + fresh.size()
						+ " passages wasn't usable — they were skipped."));
			}
		}

		events.add(log("INFO", step, "Extracted " + (saved + reused) + " claims from " + items.size() + " passages"
				+ (reused > 0 ? " (" + reused + " reused from earlier research, no AI request)." : ".")));
		if (notInSource + notGrounded > 0) {
			events.add(log("WARN", step, (notInSource + notGrounded) + " claims were dropped: "
					+ (notInSource > 0 ? notInSource + " quoted text that isn't in the source" : "")
					+ (notInSource > 0 && notGrounded > 0 ? ", " : "")
					+ (notGrounded > 0 ? notGrounded + " weren't about their quote" : "") + "."));
		}
		if (ignored > 0) {
			events.add(log("WARN", step, "Ignored " + ignored + " claims that pointed to unknown passages."));
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("passages", items.size());
		output.put("newClaims", saved);
		output.put("reusedClaims", reused);
		output.put("droppedNotInSource", notInSource);
		output.put("droppedNotGrounded", notGrounded);
		output.put("ignored", ignored);
		output.put("llmCalls", calls);
		return new StepResult(output, events);
	}

	/** One EXTRACT call for the uncached passages; null when the model's answer stayed unusable after retries. */
	private ExtractOutput call(StepContext step, Map<String, Item> byAlias, Map<UUID, Passage> passages) {
		Set<UUID> questionIds = new LinkedHashSet<>();
		byAlias.values().forEach(i -> {
			if (i.subQuestionId() != null) {
				questionIds.add(i.subQuestionId());
			}
		});
		StringBuilder questions = new StringBuilder();
		for (SubQuestionRow q : subQuestions.forRun(step.runId())) {
			if (questionIds.isEmpty() || questionIds.contains(q.id())) {
				questions.append("- ").append(Planner.data(q.text())).append('\n');
			}
		}
		StringBuilder blocks = new StringBuilder();
		byAlias.forEach((alias, item) -> {
			Passage p = passages.get(item.chunkId());
			blocks.append("<source id=\"").append(alias).append("\" title=\"")
					.append(Planner.data(p.sourceTitle()).replace("\"", "'")).append("\">\n")
					.append(Planner.data(p.text())).append("\n</source>\n\n");
		});
		Prompt prompt = prompts.load(PROMPT, PROMPT_VERSION,
				Map.of("questions", questions.toString().strip(), "passages", blocks.toString().strip()), 0.1, 3000);
		try {
			return gateway.call(step.ownerId(), TaskSlot.EXTRACT, prompt, ExtractOutput.class,
					CallContext.step(step.runId(), step.stepId())).value();
		}
		catch (NoModelForSlotException e) {
			throw new StepFailedException("No AI model is set for extracting claims. Choose one for \"Extract\" in "
					+ "Settings → AI, then retry this step.");
		}
		catch (ProviderUnavailableException e) {
			throw new StepFailedException("Your AI provider didn't answer — it was busy or down. Anveshan will try again.",
					true);
		}
		catch (InvalidLlmOutputException e) {
			return null; // skip this batch's new claims; the rest of the research goes on
		}
	}

	private static NewEvent log(String level, StepContext step, String message) {
		return new NewEvent("log", Map.of("stepId", step.stepId(), "level", level, "message", message));
	}

}
