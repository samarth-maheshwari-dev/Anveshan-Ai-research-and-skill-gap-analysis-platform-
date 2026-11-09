package dev.anveshan.byok.infra;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.app.LlmTransport;
import dev.anveshan.byok.app.ProviderModels.ChatMessage;
import dev.anveshan.byok.app.ProviderModels.CompletionOptions;
import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.RawCompletion;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.app.ProviderModels.ResolvedRoute;
import dev.anveshan.byok.domain.ProviderPreset;

/** Tavily search key (kind SEARCH): only the connection test lives here; searching comes in P11. */
@Component
class TavilyTransport implements LlmTransport {

	private final ProviderHttp http;

	TavilyTransport(ProviderHttp http) {
		this.http = http;
	}

	@Override
	public ProviderPreset.Transport kind() {
		return ProviderPreset.Transport.TAVILY;
	}

	@Override
	public RawCompletion complete(ResolvedRoute route, List<ChatMessage> messages, CompletionOptions options) {
		throw new UnsupportedOperationException("Tavily is a search key, not an LLM");
	}

	@Override
	public List<ModelInfo> models(ResolvedConnection connection) {
		return List.of();
	}

	@Override
	public String test(ResolvedConnection c, String model) {
		var reply = http.post(http.endpoint(c, c.preset().chatPath()),
				Map.of("Authorization", "Bearer " + c.secret().reveal()), "{\"query\":\"test\",\"max_results\":1}");
		if (!reply.ok()) {
			throw http.error(reply);
		}
		return null;
	}

}
