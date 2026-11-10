package dev.anveshan.byok.domain;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * How to talk to one provider (TECHSPEC §10.1). Values live in {@code application.yml}
 * ({@code anveshan.providers.*}), not in code, so they can be corrected without a rebuild.
 *
 * @param baseUrl         preset base URL; null for CUSTOM (user supplies it)
 * @param testModel       known-good model tried first by the connection test — but only when the provider
 *                        lists it (or lists nothing), so a retired hint can't break the test; null = none
 * @param preferredModels regexes, best first, used by {@link ModelRanking} to order the live model list
 */
public record ProviderPreset(Transport transport, URI baseUrl, String chatPath, String modelsPath, boolean jsonMode,
		String testModel, List<String> preferredModels) {

	public enum Transport {
		OPENAI_COMPATIBLE, ANTHROPIC, TAVILY
	}

	public ProviderPreset {
		Objects.requireNonNull(transport, "transport");
		Objects.requireNonNull(chatPath, "chatPath");
		if (baseUrl != null && !baseUrl.toString().endsWith("/")) {
			baseUrl = URI.create(baseUrl + "/");
		}
		testModel = testModel == null || testModel.isBlank() ? null : testModel.strip();
		preferredModels = preferredModels == null ? List.of() : List.copyOf(preferredModels);
	}

}
