package dev.anveshan.sources.infra;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External search APIs ({@code anveshan.search.*}, TECHSPEC §11). Every API is configured here — base URL, the
 * platform key if any, its rate limit and a daily budget — so tests point them at a local fake.
 *
 * @param userAgent         sent to every API (Wikipedia and Crossref ask for a descriptive one); no personal data
 * @param contactEmail      optional {@code mailto} for Crossref's / OpenAlex's polite pool (empty = not sent)
 * @param resultsPerQuery   how many results to ask each API for, per query
 * @param queriesPerAdapter how many of the plan's queries each API runs (the first, best ones)
 * @param cacheTtl          search responses are cached this long per (API, query, filters)
 * @param retryWait         first retry wait for a 5xx/429/network error (doubles each time, 3 attempts)
 * @param adapterTimeout    an API that takes longer for one sub-question is treated as failed
 * @param apis              per API: openalex, semanticscholar, arxiv, crossref, wikipedia, github, searxng, tavily
 */
@ConfigurationProperties("anveshan.search")
public record SearchProperties(String userAgent, String contactEmail, int resultsPerQuery, int queriesPerAdapter,
		Duration cacheTtl, Duration retryWait, Duration adapterTimeout, Map<String, Api> apis) {

	/**
	 * @param baseUrl            empty = the API is off (e.g. SearXNG when no instance runs)
	 * @param apiKey             platform key (OpenAlex, Semantic Scholar, GitHub); empty = keyless (lower limits)
	 * @param limitForPeriod     requests allowed per {@code limitRefreshPeriod}
	 * @param limitRefreshPeriod rate-limit window
	 * @param timeout            max wait for a rate-limit permit
	 * @param dailyBudget        max requests per UTC day for this API (0 = no budget); then it is skipped until tomorrow
	 */
	public record Api(boolean enabled, String baseUrl, String apiKey, int limitForPeriod, Duration limitRefreshPeriod,
			Duration timeout, int dailyBudget) {

		public boolean usable() {
			return enabled && baseUrl != null && !baseUrl.isBlank();
		}

		public boolean hasKey() {
			return apiKey != null && !apiKey.isBlank();
		}

	}

	public SearchProperties {
		if (userAgent == null || userAgent.isBlank() || resultsPerQuery < 1 || queriesPerAdapter < 1 || cacheTtl == null
				|| retryWait == null || adapterTimeout == null || apis == null) {
			throw new IllegalStateException("anveshan.search.* (user-agent, results-per-query, queries-per-adapter, "
					+ "cache-ttl, retry-wait, adapter-timeout, apis) are required");
		}
		apis = Map.copyOf(apis);
	}

	/** The API's settings; a missing entry means "off". */
	public Api api(String name) {
		return apis.getOrDefault(name, new Api(false, "", "", 1, Duration.ofSeconds(1), Duration.ofSeconds(5), 0));
	}

}
