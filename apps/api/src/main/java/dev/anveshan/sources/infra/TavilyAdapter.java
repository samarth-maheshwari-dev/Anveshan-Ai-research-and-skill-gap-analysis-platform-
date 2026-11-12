package dev.anveshan.sources.infra;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.WebSearch;
import dev.anveshan.byok.api.WebSearch.WebQuery;
import dev.anveshan.byok.api.WebSearch.WebSearchFailedException;
import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;
import dev.anveshan.sources.api.SourceSearch.SourceKind;
import dev.anveshan.sources.app.SearchAdapter;
import dev.anveshan.sources.infra.SearchHttp.SearchApiException;
import dev.anveshan.sources.infra.SearchResilience.UpstreamException;

/**
 * General web search with the user's own Tavily key (BYOK search). Only offered when the user has a verified key
 * (T-11-07); the key itself stays inside the byok module.
 */
@Component
class TavilyAdapter implements SearchAdapter {

	static final String NAME = "tavily";

	private final WebSearch webSearch;
	private final SearchProperties props;

	TavilyAdapter(WebSearch webSearch, SearchProperties props) {
		this.webSearch = webSearch;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "Tavily web search";
	}

	@Override
	public Set<SourceKind> kinds() {
		return Set.of(SourceKind.WEB, SourceKind.BLOGS, SourceKind.DOCS);
	}

	@Override
	public boolean isAvailableFor(UUID ownerId) {
		return props.api(NAME).enabled() && webSearch.available(ownerId);
	}

	@Override
	public List<Candidate> search(AdapterQuery q) {
		try {
			return webSearch.search(q.ownerId(),
					new WebQuery(q.text(), q.maxResults(), q.includeSites(), q.excludeSites(), q.since()))
					.stream()
					.map(r -> new Candidate(AdapterSupport.text(r.title()), r.url(), null, AdapterSupport.text(r.snippet()),
							r.published(), CandidateType.WEB_PAGE, NAME, 0))
					.filter(c -> c.title() != null)
					.toList();
		}
		catch (WebSearchFailedException e) {
			if (e.retryable()) {
				throw new UpstreamException("web search unavailable");
			}
			throw new SearchApiException(e.getMessage());
		}
	}

}
