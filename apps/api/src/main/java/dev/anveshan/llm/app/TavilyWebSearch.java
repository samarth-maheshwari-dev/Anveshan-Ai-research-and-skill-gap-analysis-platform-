package dev.anveshan.llm.app;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.anveshan.llm.api.WebSearch;
import dev.anveshan.llm.app.ProviderModels.ProviderAuthException;
import dev.anveshan.llm.app.ProviderModels.ProviderException;
import dev.anveshan.llm.app.ProviderModels.ProviderRateLimitException;

/**
 * Web search with the platform's Tavily key (from the environment; users never supply one). The key is used for one
 * request at a time and never logged or returned; provider error bodies are never passed on.
 */
@Service
class TavilyWebSearch implements WebSearch {

	private static final Logger log = LoggerFactory.getLogger(TavilyWebSearch.class);

	private final WebSearchTransport transport;

	TavilyWebSearch(WebSearchTransport transport) {
		this.transport = transport;
	}

	@Override
	public boolean available(UUID ownerId) {
		return transport.configured();
	}

	@Override
	public List<WebResult> search(UUID ownerId, WebQuery query) {
		if (!transport.configured()) {
			throw new WebSearchFailedException("Web search is not set up on this server.", false);
		}
		try {
			return transport.search(query);
		}
		catch (ProviderAuthException e) {
			log.warn("Web search key rejected: {}", e.code());
			throw new WebSearchFailedException("Web search is not available right now.", false);
		}
		catch (ProviderRateLimitException e) {
			throw new WebSearchFailedException("Web search hit its limit for now.", true);
		}
		catch (ProviderException e) {
			log.warn("Web search failed: {}", e.code()); // the code only (e.g. PROVIDER_UNAVAILABLE), never the body
			throw new WebSearchFailedException("The web search provider didn't answer.", true);
		}
	}

}
