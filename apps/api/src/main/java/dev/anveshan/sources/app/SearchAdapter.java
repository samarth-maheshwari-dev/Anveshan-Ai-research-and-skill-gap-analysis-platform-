package dev.anveshan.sources.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.SourceKind;

/**
 * One search API (TECHSPEC §11.2 {@code SourceSearcher}), implemented in infra. Returns raw candidates (URLs and DOIs
 * not yet canonical); throws on failure — the caller turns that into "this API is missing", never a failed run.
 */
public interface SearchAdapter {

	/** Stable id, e.g. {@code openalex}; also the {@code anveshan.search.apis} key. */
	String name();

	/** Display name for the live log, e.g. "Semantic Scholar". */
	String label();

	Set<SourceKind> kinds();

	/** Configured and, for BYOK APIs, the user has a key. */
	boolean isAvailableFor(UUID ownerId);

	List<Candidate> search(AdapterQuery query);

	/**
	 * @param since        only work published on/after this day (null = any), where the API can filter
	 * @param includeSites preferred host names (web APIs that support it)
	 * @param excludeSites host names to leave out (web APIs that support it; always filtered again afterwards)
	 */
	record AdapterQuery(UUID ownerId, String text, LocalDate since, int maxResults, List<String> includeSites,
			List<String> excludeSites) {
	}

}
