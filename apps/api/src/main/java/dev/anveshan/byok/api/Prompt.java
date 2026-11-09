package dev.anveshan.byok.api;

import java.util.Objects;

/**
 * One prompt for {@link LlmGateway}. {@code name}/{@code version} identify the prompt file
 * ({@code prompts/<name>/v<version>.st}) for caching and evaluation.
 */
public record Prompt(String name, int version, String system, String user, double temperature, int maxTokens) {

	public Prompt {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(system, "system");
		Objects.requireNonNull(user, "user");
		if (version < 1) {
			throw new IllegalArgumentException("version must be >= 1");
		}
		if (temperature < 0 || temperature > 2) {
			throw new IllegalArgumentException("temperature must be 0..2");
		}
		if (maxTokens < 1 || maxTokens > 32_000) {
			throw new IllegalArgumentException("maxTokens must be 1..32000");
		}
	}

	public static Prompt of(String name, int version, String system, String user) {
		return new Prompt(name, version, system, user, 0.2, 2048);
	}

}
