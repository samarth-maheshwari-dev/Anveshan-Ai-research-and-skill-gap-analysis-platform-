package dev.anveshan.sources.api;

import java.util.List;
import java.util.UUID;

import dev.anveshan.sources.api.SourceSearch.Candidate;

/**
 * Turns found candidates (and uploads) into clean, chunked, embedded, credibility-rated sources (TECHSPEC §12.4–12.5)
 * and serves a run's most relevant passages. PUBLIC sources are shared: the same URL/DOI is fetched and embedded
 * once for everyone. PRIVATE sources (uploads) belong to their owner and are never matched for anyone else.
 */
public interface SourceLibrary {

	/** A web candidate → a PUBLIC source (reused when it exists). Never throws for a page that can't be read. */
	IngestResult ingest(Candidate candidate);

	/**
	 * Reads ranked candidates best-first, a few at a time, until {@code needed} are usable (NEW or REUSED) or the
	 * list ends. Returns one result per candidate tried, in rank order — spares that weren't needed aren't fetched.
	 */
	List<IngestResult> ingestBest(List<Candidate> ranked, int needed);

	/** A user's file → a PRIVATE source owned by {@code ownerId} (C-FR-4; the upload screen arrives in P18). */
	IngestResult ingestUpload(UUID ownerId, String filename, String contentType, byte[] bytes, UploadPurpose purpose);

	/**
	 * Marks a source as used by a run. A PRIVATE source is linked only when {@code ownerId} owns it.
	 *
	 * @return false when nothing was linked (not allowed, or already linked to this run)
	 */
	boolean useInRun(UUID ownerId, UUID runId, UUID sourceId, UUID subQuestionId, int rank);

	/** The run's passages most similar to {@code question}, only from sources the run USES (T-12-07). */
	List<RelevantChunk> topChunks(UUID runId, String question, int k);

	/** What a reference needs about each source (citations, References list, Report view). */
	List<SourceInfo> describe(List<UUID> sourceIds);

	/** Every source linked to the run, best rank first, with its status (Live Room, E-FR-5). */
	List<RunSource> runSources(UUID runId);

	/**
	 * E-FR-5: the user skips a source — its claims won't be used in the report. Only a USED source of this run.
	 *
	 * @return false when the source isn't a USED source of the run
	 */
	boolean skip(UUID runId, UUID sourceId);

	record SourceInfo(UUID id, String title, List<String> authors, String site, java.time.LocalDate publishedAt,
			String url, String doi, String type, String tier, String tierReason) {
	}

	record RunSource(UUID sourceId, String title, String site, String url, String type, String tier, String tierReason,
			String status, Integer rank, UUID subQuestionId, int claims) {
	}

	enum Outcome {
		NEW, REUSED, UNAVAILABLE
	}

	enum UploadPurpose {
		RUN_ATTACHMENT, LIBRARY, RESUME
	}

	/**
	 * @param sourceId null when UNAVAILABLE
	 * @param reason   why it couldn't be read (UNAVAILABLE), plain words
	 * @param tier     HIGH / MEDIUM / LOW, with {@code tierReason} ("Peer-reviewed paper, 2023")
	 */
	record IngestResult(UUID sourceId, Outcome outcome, String reason, String title, String url, String tier,
			String tierReason, int chunks) {

		public static IngestResult unavailable(String url, String title, String reason) {
			return new IngestResult(null, Outcome.UNAVAILABLE, reason, title, url, null, null, 0);
		}

	}

	record RelevantChunk(UUID chunkId, UUID sourceId, int position, Integer page, double similarity) {
	}

}
