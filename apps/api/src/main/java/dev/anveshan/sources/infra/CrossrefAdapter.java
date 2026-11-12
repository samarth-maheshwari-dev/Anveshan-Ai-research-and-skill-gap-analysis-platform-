package dev.anveshan.sources.infra;

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

/** Crossref works (TECHSPEC §11.1): DOI metadata, venues, dates. Polite pool ≤ 3 requests/second. */
@Component
class CrossrefAdapter implements SearchAdapter {

	static final String NAME = "crossref";

	private final SearchHttp http;
	private final SearchProperties props;

	CrossrefAdapter(SearchHttp http, SearchProperties props) {
		this.http = http;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "Crossref";
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
		Map<String, String> p = new LinkedHashMap<>();
		p.put("query.bibliographic", q.text());
		p.put("rows", Integer.toString(q.maxResults()));
		p.put("select", "DOI,title,URL,abstract,issued,type");
		p.put("filter", q.since() == null ? null : "from-pub-date:" + q.since());
		p.put("mailto", props.contactEmail());
		JsonNode root = http.getJson(AdapterSupport.uri(props.api(NAME).baseUrl(), "works", p), Map.of());
		List<Candidate> out = new ArrayList<>();
		for (JsonNode item : root.path("message").path("items")) {
			JsonNode titles = item.path("title");
			String title = titles.isArray() && !titles.isEmpty() ? AdapterSupport.text(titles.get(0).asString("")) : null;
			String doiId = AdapterSupport.str(item, "DOI");
			if (title == null || doiId == null) {
				continue;
			}
			String doi = "https://doi.org/" + doiId;
			out.add(new Candidate(title, doi, doi, AdapterSupport.text(AdapterSupport.str(item, "abstract")),
					issued(item.path("issued").path("date-parts")),
					"posted-content".equals(AdapterSupport.str(item, "type")) ? CandidateType.PREPRINT : CandidateType.PAPER,
					NAME, 0));
		}
		return out;
	}

	/** {@code [[2023, 5, 17]]} (month and day optional) → a date. */
	static LocalDate issued(JsonNode parts) {
		if (!parts.isArray() || parts.isEmpty() || !parts.get(0).isArray() || parts.get(0).isEmpty()) {
			return null;
		}
		JsonNode p = parts.get(0);
		int y = p.get(0).asInt(0);
		if (y < 1000) {
			return null;
		}
		int m = p.size() > 1 ? Math.clamp(p.get(1).asInt(1), 1, 12) : 1;
		int d = p.size() > 2 ? Math.clamp(p.get(2).asInt(1), 1, 28) : 1;
		return LocalDate.of(y, m, d);
	}

}
