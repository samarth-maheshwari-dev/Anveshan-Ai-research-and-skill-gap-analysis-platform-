package dev.anveshan.byok.domain;

import java.net.URI;
import java.util.Objects;

/**
 * How to talk to one provider (TECHSPEC §10.1). Values live in {@code application.yml}
 * ({@code anveshan.providers.*}), not in code, so they can be corrected without a rebuild.
 *
 * @param baseUrl    preset base URL; null for CUSTOM (user supplies it)
 * @param testModel  model used by the connection test; null = first model from the models list
 */
public record ProviderPreset(Transport transport, URI baseUrl, String chatPath, String modelsPath, boolean jsonMode,
		String testModel) {

	public enum Transport {
		OPENAI_COMPATIBLE, ANTHROPIC, TAVILY
	}

	public ProviderPreset {
		Objects.requireNonNull(transport, "transport");
		Objects.requireNonNull(chatPath, "chatPath");
		if (baseUrl != null && !baseUrl.toString().endsWith("/")) {
			baseUrl = URI.create(baseUrl + "/");
		}
	}

}
