package dev.anveshan.sources.infra;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/** English Wikipedia search (TECHSPEC §11.1): background definitions. Edit dates aren't publication dates. */
@Component
class WikipediaAdapter implements SearchAdapter {

	static final String NAME = "wikipedia";

	private final SearchHttp http;
	private final SearchProperties props;

	WikipediaAdapter(SearchHttp http, SearchProperties props) {
		this.http = http;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "Wikipedia";
	}

	@Override
	public Set<SourceKind> kinds() {
		return Set.of(SourceKind.WEB, SourceKind.DOCS);
	}

	@Override
	public boolean isAvailableFor(UUID ownerId) {
		return props.api(NAME).usable();
	}

	@Override
	public List<Candidate> search(AdapterQuery q) {
		String base = props.api(NAME).baseUrl();
		Map<String, String> p = new LinkedHashMap<>();
		p.put("action", "query");
		p.put("list", "search");
		p.put("srsearch", q.text());
		p.put("srlimit", Integer.toString(Math.min(q.maxResults(), 5))); // background only: a few articles are enough
		p.put("srprop", "snippet");
		p.put("format", "json");
		p.put("utf8", "1");
		JsonNode root = http.getJson(AdapterSupport.uri(base, "w/api.php", p), Map.of());
		String wiki = (base.endsWith("/") ? base : base + "/") + "wiki/";
		List<Candidate> out = new ArrayList<>();
		for (JsonNode hit : root.path("query").path("search")) {
			String title = AdapterSupport.str(hit, "title");
			if (title == null) {
				continue;
			}
			String path = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
			out.add(new Candidate(title, wiki + path, null, AdapterSupport.text(AdapterSupport.str(hit, "snippet")), null,
					CandidateType.ENCYCLOPEDIA, NAME, 0));
		}
		return out;
	}

}
