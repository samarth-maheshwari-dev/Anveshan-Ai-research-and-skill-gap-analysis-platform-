package dev.anveshan.sources.infra;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;
import dev.anveshan.sources.api.SourceSearch.SourceKind;
import dev.anveshan.sources.app.SearchAdapter;
import tools.jackson.databind.JsonNode;

/**
 * Free fallback web search through the platform's own SearXNG instance (TECHSPEC §11.1), for users without a Tavily
 * key. Off when no instance is configured. Its upstream engines may rate-limit; then this API just goes missing.
 */
@Component
class SearxngAdapter implements SearchAdapter {

	static final String NAME = "searxng";

	private final SearchHttp http;
	private final SearchProperties props;
	private final Clock clock;

	SearxngAdapter(SearchHttp http, SearchProperties props, Clock clock) {
		this.http = http;
		this.props = props;
		this.clock = clock;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "Web search";
	}

	@Override
	public Set<SourceKind> kinds() {
		return Set.of(SourceKind.WEB, SourceKind.BLOGS, SourceKind.DOCS);
	}

	@Override
	public boolean isAvailableFor(UUID ownerId) {
		return props.api(NAME).usable();
	}

	@Override
	public List<Candidate> search(AdapterQuery q) {
		String text = q.text();
		if (q.includeSites() != null && !q.includeSites().isEmpty()) {
			text = text + " site:" + q.includeSites().get(0); // SearXNG passes site: through to most engines
		}
		Map<String, String> p = new LinkedHashMap<>();
		p.put("q", text);
		p.put("format", "json");
		p.put("language", "en");
		// SearXNG only knows day / month / year; anything older than a year means "any time"
		if (q.since() != null && !q.since().isBefore(LocalDate.now(clock).minusYears(1))) {
			p.put("time_range", "year");
		}
		JsonNode root = http.getInternalJson(AdapterSupport.uri(props.api(NAME).baseUrl(), "search", p));
		List<Candidate> out = new ArrayList<>();
		for (JsonNode r : root.path("results")) {
			String title = AdapterSupport.text(AdapterSupport.str(r, "title"));
			String url = AdapterSupport.str(r, "url");
			if (title == null || url == null) {
				continue;
			}
			out.add(new Candidate(title, url, null, AdapterSupport.text(AdapterSupport.str(r, "content")),
					AdapterSupport.date(AdapterSupport.str(r, "publishedDate")), CandidateType.WEB_PAGE, NAME, 0));
			if (out.size() >= q.maxResults()) {
				break;
			}
		}
		return out;
	}

}
