package dev.anveshan.llm.infra;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.llm.api.WebSearch.WebQuery;
import dev.anveshan.llm.api.WebSearch.WebResult;
import dev.anveshan.llm.app.ProviderModels.ProviderCallException;
import dev.anveshan.llm.app.WebSearchTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tavily web search with the developer's key ({@code TAVILY_API_KEY}, TECHSPEC §11.1). Basic depth = 1 credit. */
@Component
class TavilyTransport implements WebSearchTransport {

	private final ProviderHttp http;
	private final JsonMapper json;
	private final LlmProperties.Tavily tavily;

	TavilyTransport(ProviderHttp http, JsonMapper json, LlmProperties props) {
		this.http = http;
		this.json = json;
		this.tavily = props.tavily();
	}

	@Override
	public boolean configured() {
		return tavily.configured();
	}

	@Override
	public List<WebResult> search(WebQuery q) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", q.text());
		body.put("max_results", Math.clamp(q.maxResults(), 1, 20));
		body.put("search_depth", "basic");
		if (q.includeDomains() != null && !q.includeDomains().isEmpty()) {
			body.put("include_domains", q.includeDomains());
		}
		if (q.excludeDomains() != null && !q.excludeDomains().isEmpty()) {
			body.put("exclude_domains", q.excludeDomains());
		}
		if (q.since() != null) {
			body.put("start_date", q.since().toString());
		}
		var reply = http.post(ProviderHttp.endpoint(tavily.baseUrl(), "search"),
				Map.of("Authorization", "Bearer " + tavily.apiKey()), json.writeValueAsString(body));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		JsonNode root;
		try {
			root = json.readTree(reply.body());
		}
		catch (RuntimeException e) {
			throw new ProviderCallException("PROVIDER_ERROR", "Unreadable search response", reply.latencyMs());
		}
		List<WebResult> out = new ArrayList<>();
		for (JsonNode r : root.path("results")) {
			String url = r.path("url").asString("");
			if (url.isBlank()) {
				continue;
			}
			out.add(new WebResult(r.path("title").asString(""), url, r.path("content").asString(""),
					date(r.path("published_date").asString(""))));
		}
		return out;
	}

	private static LocalDate date(String s) {
		if (s == null || s.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(s.substring(0, 10));
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

}
