package dev.anveshan.sources.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Finds candidate sources for one sub-question from every free search API that fits (TECHSPEC §11): adapters run
 * in parallel, results are canonicalized, de-duplicated and ranked locally (embeddings, no LLM). One API failing
 * never fails the search — its results are just missing and {@link SearchOutcome#adapters()} says so.
 */
public interface SourceSearch {

	SearchOutcome search(SearchRequest request);

	/** Where to look; same names as the run's source types (PRD Module C). */
	enum SourceKind {
		WEB, ACADEMIC, GITHUB, BLOGS, DOCS
	}

	enum CandidateType {
		PAPER, PREPRINT, WEB_PAGE, ENCYCLOPEDIA, REPOSITORY
	}

	/**
	 * @param question     the sub-question (used for ranking)
	 * @param queries      search queries from the plan, best first
	 * @param since        only sources published on/after this day (null = any time)
	 * @param includeSites host names to prefer (boosted; passed to web search where it supports it)
	 * @param excludeSites host names to drop
	 * @param keep         how many ranked candidates to return
	 */
	record SearchRequest(UUID ownerId, String question, List<String> queries, Set<SourceKind> kinds, LocalDate since,
			List<String> includeSites, List<String> excludeSites, int keep) {
	}

	/**
	 * @param url     canonical URL ({@code https://doi.org/…} when only a DOI is known)
	 * @param doi     {@code https://doi.org/<lower-case doi>} or null
	 * @param adapter which API(s) found it, e.g. {@code openalex+crossref}
	 * @param score   ranking score (higher is better); relative, not a probability
	 */
	record Candidate(String title, String url, String doi, String snippet, LocalDate published, CandidateType type,
			String adapter, double score) {

		public Candidate withScore(double s) {
			return new Candidate(title, url, doi, snippet, published, type, adapter, s);
		}

	}

	enum AdapterStatus {
		OK, FAILED, SKIPPED_BUDGET, SKIPPED_CIRCUIT_OPEN
	}

	/** What one API did for this search; {@code label} is the API's display name. */
	record AdapterReport(String adapter, String label, AdapterStatus status, int results) {
	}

	record SearchOutcome(List<Candidate> candidates, List<AdapterReport> adapters) {

		/** At least one API was asked and none answered: worth retrying later (T-11-06). */
		public boolean allFailed() {
			boolean asked = adapters.stream().anyMatch(a -> a.status() != AdapterStatus.SKIPPED_BUDGET);
			return asked && adapters.stream().noneMatch(a -> a.status() == AdapterStatus.OK);
		}

	}

}
