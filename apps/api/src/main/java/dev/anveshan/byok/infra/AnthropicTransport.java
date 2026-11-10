package dev.anveshan.byok.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.app.LlmTransport;
import dev.anveshan.byok.app.ProviderModels.ChatMessage;
import dev.anveshan.byok.app.ProviderModels.CompletionOptions;
import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ProviderCallException;
import dev.anveshan.byok.app.ProviderModels.RawCompletion;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.app.ProviderModels.ResolvedRoute;
import dev.anveshan.byok.domain.ProviderPreset;
import dev.anveshan.byok.domain.RateLimitHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic Messages API: {@code x-api-key}, {@code anthropic-version}, top-level {@code system},
 * {@code max_tokens} required, text from {@code content[].text} (TECHSPEC §10.2).
 */
@Component
class AnthropicTransport implements LlmTransport {

	static final String API_VERSION = "2023-06-01";

	private final ProviderHttp http;
	private final JsonMapper json;

	AnthropicTransport(ProviderHttp http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public ProviderPreset.Transport kind() {
		return ProviderPreset.Transport.ANTHROPIC;
	}

	@Override
	public RawCompletion complete(ResolvedRoute route, List<ChatMessage> messages, CompletionOptions o) {
		ResolvedConnection c = route.connection();
		String system = messages.stream().filter(m -> m.role().equals("system")).map(ChatMessage::content)
				.collect(Collectors.joining("\n\n"));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", route.model());
		if (!system.isBlank()) {
			body.put("system", system);
		}
		body.put("messages", messages.stream().filter(m -> !m.role().equals("system"))
				.map(m -> Map.of("role", m.role(), "content", m.content())).toList());
		body.put("max_tokens", o.maxTokens());
		body.put("temperature", Math.min(o.temperature(), 1.0));

		var reply = http.post(http.endpoint(c, c.preset().chatPath()), headers(c), json.writeValueAsString(body));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		JsonNode root;
		try {
			root = json.readTree(reply.body());
		}
		catch (RuntimeException e) {
			throw new ProviderCallException("BAD_RESPONSE", "The provider sent an unreadable response", reply.latencyMs());
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode block : root.path("content")) {
			if ("text".equals(block.path("type").asString(""))) {
				text.append(block.path("text").asString(""));
			}
		}
		if (text.isEmpty()) {
			throw new ProviderCallException("EMPTY_RESPONSE", "The provider returned no text", reply.latencyMs());
		}
		JsonNode usage = root.path("usage");
		return new RawCompletion(text.toString(), OpenAiCompatibleTransport.intOrNull(usage.path("input_tokens")),
				OpenAiCompatibleTransport.intOrNull(usage.path("output_tokens")), reply.latencyMs(),
				RateLimitHeaders.remainingRequests(reply.headers()::getFirst),
				RateLimitHeaders.remainingTokens(reply.headers()::getFirst));
	}

	@Override
	public List<ModelInfo> models(ResolvedConnection c) {
		var reply = http.get(http.endpoint(c, c.preset().modelsPath()), headers(c));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		List<ModelInfo> out = new ArrayList<>();
		for (JsonNode m : json.readTree(reply.body()).path("data")) {
			String id = m.path("id").asString("");
			if (!id.isBlank()) {
				out.add(new ModelInfo(id, m.path("display_name").asString(id), false));
			}
		}
		return out;
	}

	/** Smallest valid Messages request ({@code max_tokens} is mandatory here). Any 2xx proves key + model. */
	@Override
	public String test(ResolvedConnection c, String model) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", List.of(Map.of("role", "user", "content", "Reply with OK")));
		body.put("max_tokens", 16);
		var reply = http.post(http.endpoint(c, c.preset().chatPath()), headers(c), json.writeValueAsString(body));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		return model;
	}

	private static Map<String, String> headers(ResolvedConnection c) {
		return Map.of("x-api-key", c.secret().reveal(), "anthropic-version", API_VERSION);
	}

}
