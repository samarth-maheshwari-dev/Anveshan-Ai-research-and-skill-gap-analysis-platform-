package dev.anveshan.sources.infra;

import java.util.ArrayList;
import java.util.HashMap;
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
 * GitHub repository search (TECHSPEC §11.1): repos as sources. Platform token optional (60 requests/hour without,
 * 5,000 with); user-linked tokens come with the Skill Proof Engine (P22).
 */
@Component
class GitHubAdapter implements SearchAdapter {

	static final String NAME = "github";

	private final SearchHttp http;
	private final SearchProperties props;

	GitHubAdapter(SearchHttp http, SearchProperties props) {
		this.http = http;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "GitHub";
	}

	@Override
	public Set<SourceKind> kinds() {
		return Set.of(SourceKind.GITHUB);
	}

	@Override
	public boolean isAvailableFor(UUID ownerId) {
		return props.api(NAME).usable();
	}

	@Override
	public List<Candidate> search(AdapterQuery q) {
		SearchProperties.Api api = props.api(NAME);
		Map<String, String> p = new LinkedHashMap<>();
		p.put("q", q.since() == null ? q.text() : q.text() + " pushed:>=" + q.since());
		p.put("per_page", Integer.toString(q.maxResults()));
		Map<String, String> headers = new HashMap<>();
		headers.put("Accept", "application/vnd.github+json");
		headers.put("X-GitHub-Api-Version", "2022-11-28");
		if (api.hasKey()) {
			headers.put("Authorization", "Bearer " + api.apiKey());
		}
		JsonNode root = http.getJson(AdapterSupport.uri(api.baseUrl(), "search/repositories", p), headers);
		List<Candidate> out = new ArrayList<>();
		for (JsonNode repo : root.path("items")) {
			String name = AdapterSupport.str(repo, "full_name");
			String url = AdapterSupport.str(repo, "html_url");
			if (name == null || url == null) {
				continue;
			}
			String description = AdapterSupport.str(repo, "description");
			int stars = repo.path("stargazers_count").asInt(0);
			String snippet = (description == null ? "" : description + " ") + "(" + stars + " stars)";
			out.add(new Candidate(name, url, null, AdapterSupport.text(snippet),
					AdapterSupport.date(AdapterSupport.str(repo, "pushed_at")), CandidateType.REPOSITORY, NAME, 0));
		}
		return out;
	}

}
