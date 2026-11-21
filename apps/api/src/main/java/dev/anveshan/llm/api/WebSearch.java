package dev.anveshan.llm.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * General web search with the platform search key (TECHSPEC §11.1: Tavily, key from the environment). The key never leaves
 * the llm module; callers get results only.
 */
public interface WebSearch {

	/** A platform search key is configured. */
	boolean available(UUID ownerId);

	/**
	 * @throws WebSearchFailedException the key is missing or rejected, or the provider failed (message is safe to
	 *                                  show; never contains the key or the provider's error body)
	 */
	List<WebResult> search(UUID ownerId, WebQuery query);

	/** {@code since} = only pages published on/after this day, when the provider supports it. */
	record WebQuery(String text, int maxResults, List<String> includeDomains, List<String> excludeDomains,
			LocalDate since) {
	}

	record WebResult(String title, String url, String snippet, LocalDate published) {
	}

	class WebSearchFailedException extends RuntimeException {

		private final boolean retryable;

		public WebSearchFailedException(String safeMessage, boolean retryable) {
			super(safeMessage);
			this.retryable = retryable;
		}

		/** A busy or unreachable provider (5xx, 429, network) — not a bad key. */
		public boolean retryable() {
			return retryable;
		}

	}

}
