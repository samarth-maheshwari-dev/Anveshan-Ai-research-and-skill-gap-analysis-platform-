package dev.anveshan.research.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.research.domain.ResearchEnums.StepKind;
import dev.anveshan.research.domain.ResearchSize;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.sources.api.ClaimLibrary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real stage graph (TECHSPEC §12.1), grown phase by phase:
 * 0 PLAN → 1 SEARCH (per sub-question) → 2 FETCH (per sub-question) → 3 RELEVANCE (per sub-question) →
 * 4 EXTRACT (per batch of ≤ 3 sources × top chunks) → 5 VERIFY (per 9 claims) → 6 LABEL (run) → 7 WRITE (P14).
 * Every stage gets at least one step (an empty batch is fine), so the stage numbers always mean the same thing.
 * Runs inside the engine's transaction: database reads only, never the network.
 */
@Component
class ResearchStages implements StageMaterializer {

	static final int SOURCES_PER_EXTRACT = 3;
	static final int CLAIMS_PER_VERIFY = 9;

	private final SubQuestionStore subQuestions;
	private final StepStore steps;
	private final ClaimLibrary claims;
	private final JsonMapper json;

	ResearchStages(SubQuestionStore subQuestions, StepStore steps, ClaimLibrary claims, JsonMapper json) {
		this.subQuestions = subQuestions;
		this.steps = steps;
		this.claims = claims;
		this.json = json;
	}

	@Override
	public List<NewStep> next(RunSnapshot run, int closedStage) {
		return switch (closedStage) {
			case 0 -> perSubQuestion(run, StepKind.SEARCH);
			case 1 -> perSubQuestion(run, StepKind.FETCH);
			case 2 -> perSubQuestion(run, StepKind.RELEVANCE);
			case 3 -> extractBatches(run);
			case 4 -> verifyBatches(run);
			case 5 -> List.of(new NewStep(StepKind.LABEL, null, Map.of()));
			case 6 -> List.of(new NewStep(StepKind.WRITE_SECTION, null, Map.of())); // handler + per-section steps: P14
			default -> List.of();
		};
	}

	private List<NewStep> perSubQuestion(RunSnapshot run, StepKind kind) {
		return subQuestions.forRun(run.id()).stream()
				.map(q -> new NewStep(kind, q.id(), Map.of("subQuestionId", q.id().toString())))
				.toList();
	}

	/**
	 * The RELEVANCE picks of every sub-question, each chunk once, at most chunks-per-source per (sub-question,
	 * source), grouped by source into batches of ≤ 3 sources (TD-3).
	 */
	private List<NewStep> extractBatches(RunSnapshot run) {
		ResearchSize size = json.readValue(run.configJson(), RunConfig.class).resolved().size();
		Set<UUID> seenChunks = new HashSet<>();
		Map<String, Integer> perQuestionSource = new HashMap<>();
		Map<UUID, List<Map<String, Object>>> bySource = new LinkedHashMap<>();
		for (var q : subQuestions.forRun(run.id())) {
			String out = steps.doneOutput(run.id(), StepKind.RELEVANCE, q.id()).orElse(null);
			if (out == null) {
				continue;
			}
			for (JsonNode c : json.readTree(out).path("chunks")) {
				UUID chunk = UUID.fromString(c.path("chunkId").asString());
				UUID source = UUID.fromString(c.path("sourceId").asString());
				if (!seenChunks.add(chunk)
						|| perQuestionSource.merge(q.id() + "|" + source, 1, Integer::sum) > size.chunksPerSource()) {
					continue;
				}
				bySource.computeIfAbsent(source, k -> new ArrayList<>())
						.add(Map.of("chunkId", chunk.toString(), "subQuestionId", q.id().toString()));
			}
		}
		List<NewStep> out = new ArrayList<>();
		List<Map<String, Object>> batch = new ArrayList<>();
		int sourcesInBatch = 0;
		for (List<Map<String, Object>> chunks : bySource.values()) {
			if (sourcesInBatch == SOURCES_PER_EXTRACT) {
				out.add(new NewStep(StepKind.EXTRACT, null, Map.of("items", List.copyOf(batch))));
				batch = new ArrayList<>();
				sourcesInBatch = 0;
			}
			batch.addAll(chunks);
			sourcesInBatch++;
		}
		if (!batch.isEmpty() || out.isEmpty()) {
			out.add(new NewStep(StepKind.EXTRACT, null, Map.of("items", List.copyOf(batch))));
		}
		return out;
	}

	private List<NewStep> verifyBatches(RunSnapshot run) {
		List<String> ids = claims.runClaims(run.id()).stream().map(c -> c.id().toString()).toList();
		List<NewStep> out = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += CLAIMS_PER_VERIFY) {
			out.add(new NewStep(StepKind.VERIFY, null,
					Map.of("claimIds", ids.subList(i, Math.min(ids.size(), i + CLAIMS_PER_VERIFY)))));
		}
		if (out.isEmpty()) {
			out.add(new NewStep(StepKind.VERIFY, null, Map.of("claimIds", List.of())));
		}
		return out;
	}

}
