package dev.anveshan.byok.infra;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.WebSearch.WebQuery;
import dev.anveshan.byok.api.WebSearch.WebResult;
import dev.anveshan.byok.app.LlmTransport;
import dev.anveshan.byok.app.ProviderModels.ChatMessage;
import dev.anveshan.byok.app.ProviderModels.CompletionOptions;
import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ProviderCallException;
import dev.anveshan.byok.app.ProviderModels.RawCompletion;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.app.ProviderModels.ResolvedRoute;
import dev.anveshan.byok.app.WebSearchTransport;
import dev.anveshan.byok.domain.ProviderPreset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tavily search key (kind SEARCH): the connection test and web search (P11, TECHSPEC §11.1). Basic depth = 1 credit. */
@Component
class TavilyTransport implements LlmTransport, WebSearchTransport {

	private final ProviderHttp http;
	private final JsonMapper json;

	TavilyTransport(ProviderHttp http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public ProviderPreset.Transport kind() {
		return ProviderPreset.Transport.TAVILY;
	}

	@Override
	public RawCompletion complete(ResolvedRoute route, List<ChatMessage> messages, CompletionOptions options) {
		throw new UnsupportedOperationException("Tavily is a search key, not an LLM");
	}

	@Override
	public List<ModelInfo> models(ResolvedConnection connection) {
		return List.of();
	}

	@Override
	public String test(ResolvedConnection c, String model) {
		var reply = http.post(http.endpoint(c, c.preset().chatPath()),
				Map.of("Authorization", "Bearer " + c.secret().reveal()), "{\"query\":\"test\",\"max_results\":1}");
		if (!reply.ok()) {
			throw http.error(reply);
		}
		return null;
	}

	@Override
	public List<WebResult> search(ResolvedConnection c, WebQuery q) {
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
		var reply = http.post(http.endpoint(c, c.preset().chatPath()),
				Map.of("Authorization", "Bearer " + c.secret().reveal()), json.writeValueAsString(body));
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
