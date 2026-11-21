package dev.anveshan.llm.infra;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.anveshan.llm.api.TaskSlot;

/**
 * The developer-configured LLM gateway ({@code anveshan.llm.*}): an ordered endpoint chain, the breaker, the daily
 * run allowance per user and the platform Tavily key. Keys come from the environment only and never print:
 * {@code toString()} of every record here masks them.
 */
@ConfigurationProperties("anveshan.llm")
public record LlmProperties(List<Endpoint> endpoints, int dailyRunsPerUser, Duration timeout, Breaker breaker,
		Tavily tavily) {

	/**
	 * One OpenAI-compatible endpoint. {@code model} is the default; {@code models} overrides it per slot. An endpoint
	 * with a blank key is skipped (not configured).
	 */
	public record Endpoint(String name, URI baseUrl, String apiKey, String model, Map<TaskSlot, String> models,
			boolean jsonMode) {

		public Endpoint {
			models = models == null || models.isEmpty() ? Map.of() : new EnumMap<>(models);
			if (name == null || name.isBlank() || model == null || model.isBlank()) {
				throw new IllegalStateException("anveshan.llm.endpoints[]: name and model are required");
			}
		}

		public boolean configured() {
			return baseUrl != null && apiKey != null && !apiKey.isBlank();
		}

		public String modelFor(TaskSlot slot) {
			return models.getOrDefault(slot, model);
		}

		@Override
		public String toString() {
			return "Endpoint[" + name + ", " + baseUrl + ", key=" + (configured() ? "****" : "none") + "]";
		}

	}

	/** After {@code failures} failures in a row an endpoint is skipped for {@code openFor}. */
	public record Breaker(int failures, Duration openFor) {
	}

	public record Tavily(URI baseUrl, String apiKey) {

		public boolean configured() {
			return baseUrl != null && apiKey != null && !apiKey.isBlank();
		}

		@Override
		public String toString() {
			return "Tavily[" + baseUrl + ", key=" + (configured() ? "****" : "none") + "]";
		}

	}

	public LlmProperties {
		endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
		timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
		breaker = breaker == null ? new Breaker(3, Duration.ofSeconds(60)) : breaker;
		tavily = tavily == null ? new Tavily(null, null) : tavily;
		if (dailyRunsPerUser < 1 || breaker.failures() < 1 || breaker.openFor() == null) {
			throw new IllegalStateException("anveshan.llm.daily-runs-per-user and breaker.failures must be >= 1");
		}
	}

	/** Endpoints with a key, in chain order. */
	public List<Endpoint> configured() {
		return endpoints.stream().filter(Endpoint::configured).toList();
	}

}
