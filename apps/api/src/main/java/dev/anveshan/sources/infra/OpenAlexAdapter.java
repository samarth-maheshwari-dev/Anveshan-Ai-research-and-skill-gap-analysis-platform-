package dev.anveshan.sources.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;
import dev.anveshan.sources.api.SourceSearch.SourceKind;
import dev.anveshan.sources.app.SearchAdapter;
import tools.jackson.databind.JsonNode;

/** OpenAlex works search (TECHSPEC §11.1): academic metadata, DOIs, open-access links. Platform key optional. */
@Component
class OpenAlexAdapter implements SearchAdapter {

	static final String NAME = "openalex";

	private final SearchHttp http;
	private final SearchProperties props;

	OpenAlexAdapter(SearchHttp http, SearchProperties props) {
		this.http = http;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "OpenAlex";
	}

	@Override
	public Set<SourceKind> kinds() {
		return Set.of(SourceKind.ACADEMIC);
	}

	@Override
	public boolean isAvailableFor(UUID ownerId) {
		return props.api(NAME).usable();
	}

	@Override
	public List<Candidate> search(AdapterQuery q) {
		SearchProperties.Api api = props.api(NAME);
		Map<String, String> p = new LinkedHashMap<>();
		p.put("search", q.text());
		p.put("per_page", Integer.toString(q.maxResults()));
		p.put("select", "id,doi,display_name,publication_date,type,primary_location,open_access,abstract_inverted_index");
		p.put("filter", q.since() == null ? null : "from_publication_date:" + q.since());
		p.put("api_key", api.hasKey() ? api.apiKey() : null);
		p.put("mailto", props.contactEmail());
		JsonNode root = http.getJson(AdapterSupport.uri(api.baseUrl(), "works", p), Map.of());
		List<Candidate> out = new ArrayList<>();
		for (JsonNode w : root.path("results")) {
			String title = AdapterSupport.str(w, "display_name");
			String doi = AdapterSupport.str(w, "doi");
			String url = doi != null ? doi
					: firstNonNull(AdapterSupport.str(w.path("primary_location"), "landing_page_url"),
							AdapterSupport.str(w.path("open_access"), "oa_url"), AdapterSupport.str(w, "id"));
			if (title == null || url == null) {
				continue;
			}
			CandidateType type = "preprint".equals(AdapterSupport.str(w, "type")) ? CandidateType.PREPRINT
					: CandidateType.PAPER;
			out.add(new Candidate(title, url, doi, AdapterSupport.text(abstractText(w.path("abstract_inverted_index"))),
					AdapterSupport.date(AdapterSupport.str(w, "publication_date")), type, NAME, 0));
		}
		return out;
	}

	/** OpenAlex ships abstracts as {word: [positions]}; the first 80 words are enough for a snippet. */
	static String abstractText(JsonNode index) {
		if (index == null || !index.isObject()) {
			return null;
		}
		TreeMap<Integer, String> words = new TreeMap<>();
		for (var e : index.properties()) {
			for (JsonNode pos : e.getValue()) {
				int at = pos.asInt(-1);
				if (at >= 0 && at < 80) {
					words.put(at, e.getKey());
				}
			}
		}
		return words.isEmpty() ? null : String.join(" ", words.values());
	}

	private static String firstNonNull(String... values) {
		for (String v : values) {
			if (v != null) {
				return v;
			}
		}
		return null;
	}

}
