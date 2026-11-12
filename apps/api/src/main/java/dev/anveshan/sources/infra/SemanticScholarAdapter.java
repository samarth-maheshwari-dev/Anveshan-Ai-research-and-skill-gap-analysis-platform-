package dev.anveshan.sources.infra;

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

/** Semantic Scholar paper search (TECHSPEC §11.1): abstracts and TL;DRs. ≤ 1 request/second. */
@Component
class SemanticScholarAdapter implements SearchAdapter {

	static final String NAME = "semanticscholar";

	private final SearchHttp http;
	private final SearchProperties props;

	SemanticScholarAdapter(SearchHttp http, SearchProperties props) {
		this.http = http;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "Semantic Scholar";
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
		p.put("query", q.text());
		p.put("limit", Integer.toString(q.maxResults()));
		p.put("fields", "title,url,abstract,year,publicationDate,externalIds,tldr");
		p.put("year", q.since() == null ? null : q.since().getYear() + "-");
		JsonNode root = http.getJson(AdapterSupport.uri(api.baseUrl(), "graph/v1/paper/search", p),
				api.hasKey() ? Map.of("x-api-key", api.apiKey()) : Map.of());
		List<Candidate> out = new ArrayList<>();
		for (JsonNode paper : root.path("data")) {
			String title = AdapterSupport.str(paper, "title");
			JsonNode ids = paper.path("externalIds");
			String doiId = AdapterSupport.str(ids, "DOI");
			String doi = doiId == null ? null : "https://doi.org/" + doiId;
			String arxiv = AdapterSupport.str(ids, "ArXiv");
			String url = doi != null ? doi
					: arxiv != null ? "https://arxiv.org/abs/" + arxiv : AdapterSupport.str(paper, "url");
			if (title == null || url == null) {
				continue;
			}
			String tldr = AdapterSupport.str(paper.path("tldr"), "text");
			String date = AdapterSupport.str(paper, "publicationDate");
			if (date == null && paper.path("year").isNumber()) {
				date = Integer.toString(paper.path("year").asInt());
			}
			out.add(new Candidate(title, url, doi,
					AdapterSupport.text(tldr != null ? tldr : AdapterSupport.str(paper, "abstract")),
					AdapterSupport.date(date), doi == null && arxiv != null ? CandidateType.PREPRINT : CandidateType.PAPER,
					NAME, 0));
		}
		return out;
	}

}
