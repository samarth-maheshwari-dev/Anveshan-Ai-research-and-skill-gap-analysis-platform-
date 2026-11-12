package dev.anveshan.byok.app;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.anveshan.byok.api.WebSearch;
import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.app.ProviderModels.ProviderRateLimitException;
import dev.anveshan.byok.domain.AiConnection;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import dev.anveshan.byok.infra.AiConnectionRepository;

/**
 * BYOK web search with the user's oldest verified Tavily key. The key is decrypted here, used for one request and
 * never logged or returned; provider error bodies are never passed on.
 */
@Service
class TavilyWebSearch implements WebSearch {

	private static final Logger log = LoggerFactory.getLogger(TavilyWebSearch.class);

	private final AiConnectionRepository connections;
	private final KeyVault vault;
	private final WebSearchTransport transport;

	TavilyWebSearch(AiConnectionRepository connections, KeyVault vault, WebSearchTransport transport) {
		this.connections = connections;
		this.vault = vault;
		this.transport = transport;
	}

	@Override
	public boolean available(UUID ownerId) {
		return key(ownerId).isPresent();
	}

	@Override
	public List<WebResult> search(UUID ownerId, WebQuery query) {
		AiConnection c = key(ownerId)
				.orElseThrow(() -> new WebSearchFailedException("No verified web search key.", false));
		try {
			var resolved = vault.resolve(c.getId(), ownerId, c.getProvider(), c.getBaseUrl(), c.getKeyCiphertext());
			return transport.search(resolved, query);
		}
		catch (KeyVault.KeyUnreadableException e) {
			throw new WebSearchFailedException("The saved web search key can't be read anymore. Enter it again.", false);
		}
		catch (ProviderAuthException e) {
			throw new WebSearchFailedException("The web search key was rejected. Check it in Settings → AI.", false);
		}
		catch (ProviderRateLimitException e) {
			throw new WebSearchFailedException("The web search key hit its limit for now.", true);
		}
		catch (ProviderException e) {
			log.warn("Web search failed: {}", e.code()); // the code only (e.g. PROVIDER_UNAVAILABLE), never the body
			throw new WebSearchFailedException("The web search provider didn't answer.", true);
		}
	}

	private Optional<AiConnection> key(UUID ownerId) {
		return connections.findByOwnerIdOrderByCreatedAt(ownerId).stream()
				.filter(c -> c.getKind() == ConnectionKind.SEARCH && c.getProvider() == AiProvider.TAVILY
						&& c.getStatus() == ConnectionStatus.VERIFIED)
				.findFirst(); // oldest first (repository order)
	}

}
