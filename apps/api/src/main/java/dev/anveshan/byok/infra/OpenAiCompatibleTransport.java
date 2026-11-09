package dev.anveshan.byok.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/** Gemini, Groq, OpenRouter, OpenAI and custom endpoints: {@code POST chat/completions} (TECHSPEC §10.2). */
@Component
class OpenAiCompatibleTransport implements LlmTransport {

	private final ProviderHttp http;
	private final JsonMapper json;

	OpenAiCompatibleTransport(ProviderHttp http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public ProviderPreset.Transport kind() {
		return ProviderPreset.Transport.OPENAI_COMPATIBLE;
	}

	@Override
	public RawCompletion complete(ResolvedRoute route, List<ChatMessage> messages, CompletionOptions o) {
		ResolvedConnection c = route.connection();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", route.model());
		body.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
		body.put("temperature", o.temperature());
		body.put("max_tokens", o.maxTokens());
		if (o.jsonMode() && c.preset().jsonMode()) {
			body.put("response_format", Map.of("type", "json_object"));
		}
		var reply = http.post(http.endpoint(c, c.preset().chatPath()), auth(c), json.writeValueAsString(body));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		JsonNode root = parse(reply);
		JsonNode content = root.path("choices").path(0).path("message").path("content");
		if (!content.isString()) {
			throw new ProviderCallException("EMPTY_RESPONSE", "The provider returned no text", reply.latencyMs());
		}
		JsonNode usage = root.path("usage");
		return new RawCompletion(content.asString(), intOrNull(usage.path("prompt_tokens")),
				intOrNull(usage.path("completion_tokens")), reply.latencyMs(),
				RateLimitHeaders.remainingRequests(reply.headers()::getFirst),
				RateLimitHeaders.remainingTokens(reply.headers()::getFirst));
	}

	@Override
	public List<ModelInfo> models(ResolvedConnection c) {
		if (c.preset().modelsPath() == null) {
			return List.of();
		}
		var reply = http.get(http.endpoint(c, c.preset().modelsPath()), auth(c));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		List<ModelInfo> out = new ArrayList<>();
		for (JsonNode m : parse(reply).path("data")) {
			String id = m.path("id").asString("");
			if (id.isBlank()) {
				continue;
			}
			id = id.startsWith("models/") ? id.substring("models/".length()) : id; // Gemini
			String name = m.path("name").isString() ? m.path("name").asString() : id;
			JsonNode pricing = m.path("pricing");
			boolean free = id.endsWith(":free") || (pricing.isObject() && isZero(pricing.path("prompt"))
					&& isZero(pricing.path("completion")));
			out.add(new ModelInfo(id, name, free));
		}
		return out;
	}

	@Override
	public String test(ResolvedConnection c, String model) {
		complete(new ResolvedRoute(c, model, 0), List.of(ChatMessage.user("Reply with OK")),
				new CompletionOptions(0, 5, false));
		return model;
	}

	private static Map<String, String> auth(ResolvedConnection c) {
		return Map.of("Authorization", "Bearer " + c.secret().reveal());
	}

	private JsonNode parse(ProviderHttp.Reply reply) {
		try {
			return json.readTree(reply.body());
		}
		catch (RuntimeException e) {
			throw new ProviderCallException("BAD_RESPONSE", "The provider sent an unreadable response", reply.latencyMs());
		}
	}

	private static boolean isZero(JsonNode n) {
		String s = n.asString("");
		return !s.isBlank() && s.matches("0+(\\.0+)?");
	}

	static Integer intOrNull(JsonNode n) {
		return n.isNumber() ? n.asInt() : null;
	}

}
