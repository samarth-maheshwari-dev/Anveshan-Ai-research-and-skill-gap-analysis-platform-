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
import dev.anveshan.research.domain.SectionClaims;
import dev.anveshan.research.infra.StepStore;
import dev.anveshan.research.infra.SubQuestionStore;
import dev.anveshan.sources.api.ClaimLibrary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real stage graph (TECHSPEC §12.1), grown phase by phase:
 * 0 PLAN → 1 SEARCH (per sub-question) → 2 FETCH (per sub-question) → 3 RELEVANCE (per sub-question) →
 * 4 EXTRACT (per batch of ≤ 3 sources × top chunks) → 5 VERIFY (per 9 claims) → 6 LABEL (run) →
 * 7 WRITE_SECTION (per report section) → 8 ASSEMBLE (run) → COMPLETED.
 * Every stage gets at least one step (an empty batch is fine), so the stage numbers always mean the same thing.
 * Runs inside the engine's transaction: database reads only, never the network.
 */
@Component
class ResearchStages implements StageMaterializer {

	static final int SOURCES_PER_EXTRACT = 3;
	static final int CLAIMS_PER_VERIFY = 9;
	/** A thin section borrows its closest claims up to this many (the only overlap between sections). */
	static final int MIN_CLAIMS_PER_SECTION = 3;
	/** TD token diet: never more claims than this in one WRITE call. */
	static final int MAX_CLAIMS_PER_SECTION = 25;

	private final SubQuestionStore subQuestions;
	private final StepStore steps;
	private final ClaimLibrary claims;
	private final JsonMapper json;
	private final ReportSections sections;
	private final dev.anveshan.sources.api.Embeddings embeddings;

	ResearchStages(SubQuestionStore subQuestions, StepStore steps, ClaimLibrary claims, JsonMapper json,
			ReportSections sections, dev.anveshan.sources.api.Embeddings embeddings) {
		this.subQuestions = subQuestions;
		this.steps = steps;
		this.claims = claims;
		this.json = json;
		this.sections = sections;
		this.embeddings = embeddings;
	}

	/** {@code config.mode} of a Find-better-sources child run (SCHEMA §13 S-9). */
	static final String CLAIM_REFRESH = "CLAIM_REFRESH";

	@Override
	public List<NewStep> next(RunSnapshot run, int closedStage) {
		JsonNode config = json.readTree(run.configJson());
		if (CLAIM_REFRESH.equals(config.path("mode").asString(""))) {
			return refresh(run, config, closedStage);
		}
		return switch (closedStage) {
			case 0 -> perSubQuestion(run, StepKind.SEARCH);
			case 1 -> perSubQuestion(run, StepKind.FETCH);
			case 2 -> perSubQuestion(run, StepKind.RELEVANCE);
			case 3 -> extractBatches(run);
			case 4 -> verifyBatches(run);
			case 5 -> List.of(new NewStep(StepKind.LABEL, null, Map.of()));
			case 6 -> writeSteps(run);
			case 7 -> List.of(new NewStep(StepKind.ASSEMBLE, null, Map.of()));
			default -> List.of();
		};
	}

	/**
	 * F-FR-5 / TECHSPEC §12.10: a child run looks for stronger sources for ONE claim of its parent run. Stages 1–3
	 * as usual (its one sub-question is the claim), nothing to extract (the claim exists), VERIFY judges that claim
	 * against the new passages and saves the verdicts on the PARENT run, LABEL relabels it there. No report.
	 */
	private List<NewStep> refresh(RunSnapshot run, JsonNode config, int closedStage) {
		String claimId = config.path("claimId").asString();
		String parent = config.path("parentRunId").asString();
		return switch (closedStage) {
			case 0 -> perSubQuestion(run, StepKind.SEARCH);
			case 1 -> perSubQuestion(run, StepKind.FETCH);
			case 2 -> perSubQuestion(run, StepKind.RELEVANCE);
			case 3 -> List.of(new NewStep(StepKind.EXTRACT, null, Map.of("items", List.of())));
			case 4 -> List.of(new NewStep(StepKind.VERIFY, null,
					Map.of("claimIds", List.of(claimId), "verdictRunId", parent)));
			case 5 -> List.of(new NewStep(StepKind.LABEL, null,
					Map.of("refreshRunId", parent, "claimId", claimId, "reportId", config.path("reportId").asString())));
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

	/**
	 * One WRITE step per section. Each usable claim (SUPPORTED / WEAK, source still used) goes to the section closest
	 * to it in meaning — local embeddings, no LLM — so sections don't repeat each other (SectionClaims).
	 */
	private List<NewStep> writeSteps(RunSnapshot run) {
		RunConfig config = json.readValue(run.configJson(), RunConfig.class).resolved();
		List<ReportSections.Spec> specs = sections.specs(config);
		List<List<SectionClaims.Scored>> scores = new ArrayList<>();
		for (ReportSections.Spec s : specs) {
			float[] v = embeddings.query(run.topic() + " — " + s.title() + ". " + s.instructions());
			scores.add(claims.usableMatches(run.id(), v).stream()
					.map(m -> new SectionClaims.Scored(m.claimId().toString(), m.similarity())).toList());
		}
		List<java.util.LinkedHashSet<String>> assigned = SectionClaims.assign(scores, MIN_CLAIMS_PER_SECTION,
				MAX_CLAIMS_PER_SECTION);
		List<String> titles = specs.stream().map(ReportSections.Spec::title).toList();
		List<NewStep> out = new ArrayList<>();
		for (int i = 0; i < specs.size(); i++) {
			ReportSections.Spec s = specs.get(i);
			Map<String, Object> input = new LinkedHashMap<>();
			input.put("position", i);
			input.put("key", s.key());
			input.put("title", s.title());
			input.put("instructions", s.instructions());
			input.put("allSections", titles);
			input.put("claimIds", List.copyOf(assigned.get(i)));
			out.add(new NewStep(StepKind.WRITE_SECTION, null, input));
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
