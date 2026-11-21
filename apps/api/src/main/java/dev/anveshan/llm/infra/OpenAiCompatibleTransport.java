package dev.anveshan.llm.infra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.llm.app.LlmEndpoint;
import dev.anveshan.llm.app.LlmTransport;
import dev.anveshan.llm.app.ProviderModels.ChatMessage;
import dev.anveshan.llm.app.ProviderModels.CompletionOptions;
import dev.anveshan.llm.app.ProviderModels.ProviderCallException;
import dev.anveshan.llm.app.ProviderModels.RawCompletion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** freellmapi, Gemini's OpenAI endpoint and any other {@code POST chat/completions} gateway (TECHSPEC §10.2). */
@Component
class OpenAiCompatibleTransport implements LlmTransport {

	private final ProviderHttp http;
	private final JsonMapper json;

	OpenAiCompatibleTransport(ProviderHttp http, JsonMapper json) {
		this.http = http;
		this.json = json;
	}

	@Override
	public RawCompletion complete(LlmEndpoint e, String model, List<ChatMessage> messages, CompletionOptions o) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
		body.put("temperature", o.temperature());
		body.put("max_tokens", o.maxTokens());
		if (o.jsonMode() && e.jsonMode()) {
			body.put("response_format", Map.of("type", "json_object"));
		}
		var reply = http.post(ProviderHttp.endpoint(e.baseUrl(), "chat/completions"),
				Map.of("Authorization", "Bearer " + e.key().reveal()), json.writeValueAsString(body));
		if (!reply.ok()) {
			throw http.error(reply);
		}
		JsonNode root;
		try {
			root = json.readTree(reply.body());
		}
		catch (RuntimeException ex) {
			throw new ProviderCallException("BAD_RESPONSE", "The provider sent an unreadable response", reply.latencyMs());
		}
		JsonNode content = root.path("choices").path(0).path("message").path("content");
		if (!content.isString()) {
			throw new ProviderCallException("EMPTY_RESPONSE", "The provider returned no text", reply.latencyMs());
		}
		JsonNode usage = root.path("usage");
		return new RawCompletion(content.asString(), intOrNull(usage.path("prompt_tokens")),
				intOrNull(usage.path("completion_tokens")), reply.latencyMs());
	}

	static Integer intOrNull(JsonNode n) {
		return n.isNumber() ? n.asInt() : null;
	}

}
