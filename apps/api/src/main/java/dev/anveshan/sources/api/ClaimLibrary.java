package dev.anveshan.sources.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claims anchored to exact quotes, their use in runs, verdicts and trust labels (TECHSPEC §12.6–12.8). Offsets are
 * computed here by code (L1); labels are computed here by code (L2). The LLM parts — extracting and judging — live in
 * the research steps and only ever hand text to this API.
 */
public interface ClaimLibrary {

	/** A chunk with what the prompts need: its text, its source's title and credibility tier. */
	record Passage(UUID chunkId, UUID sourceId, String text, String sourceTitle, String tier) {
	}

	/** {@code quote} is exactly {@code chunkText.substring(quoteStart, quoteEnd)}. */
	record Claim(UUID id, UUID chunkId, UUID sourceId, String text, String quote, int quoteStart, int quoteEnd) {
	}

	enum AnchorOutcome {
		SAVED, REUSED, NOT_IN_SOURCE, NOT_GROUNDED
	}

	record Anchored(AnchorOutcome outcome, Claim claim) {
	}

	record LabelCounts(int supported, int weak, int conflicting, int noSource) {

		public int total() {
			return supported + weak + conflicting + noSource;
		}

	}

	Map<UUID, Passage> passages(List<UUID> chunkIds);

	/** Claims already extracted from this chunk with this prompt version (TD-4: reuse, no LLM call). */
	List<Claim> cached(UUID chunkId, String promptVersion);

	/**
	 * Anchors a model's claim to its chunk: the quote must be found there (exact / normalized / fuzzy ≥ 0.95) and the
	 * claim must be about that quote. Otherwise nothing is saved and a metric counts it.
	 */
	Anchored anchor(UUID chunkId, String text, String quote, String promptVersion);

	void useInRun(UUID runId, UUID claimId, UUID subQuestionId);

	/** The run's claims, oldest first. */
	List<Claim> runClaims(UUID runId);

	List<Claim> claims(List<UUID> ids);

	/** The claim's own chunk first, then the {@code others} most similar chunks of OTHER sources the run uses. */
	List<Passage> verificationPassages(UUID runId, Claim claim, int others);

	void saveVerdict(UUID runId, UUID claimId, UUID chunkId, String verdict, String reason);

	/** Computes and saves every claim's trust label for the run (pure rules, §12.8). */
	LabelCounts labelRun(UUID runId);

	/** A claim of a run with its label — what the writer, the assembler and the report view need. */
	record ReportClaim(UUID id, UUID chunkId, UUID sourceId, String text, String quote, int quoteStart, int quoteEnd,
			String label, String labelReasonJson) {
	}

	/** Claims of the run by id (only claims the run uses; skipped sources' claims included — the caller filters). */
	List<ReportClaim> reportClaims(UUID runId, List<UUID> claimIds);

	/**
	 * Claims usable in the report body — labelled SUPPORTED or WEAK, from sources the run still USES — with their
	 * similarity to {@code vector} (a section's meaning), most similar first.
	 */
	List<Match> usableMatches(UUID runId, float[] vector);

	record Match(UUID claimId, double similarity) {
	}

	/** CONFLICTING claims of the run (not from skipped sources) with the sources on each side. */
	List<Conflict> conflicts(UUID runId);

	record Conflict(UUID claimId, String text, List<UUID> supportingSources, List<UUID> contradictingSources) {
	}

}
