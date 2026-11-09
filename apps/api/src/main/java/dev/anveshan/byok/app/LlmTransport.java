package dev.anveshan.byok.app;

import java.util.List;

import dev.anveshan.byok.app.ProviderModels.ChatMessage;
import dev.anveshan.byok.app.ProviderModels.CompletionOptions;
import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.RawCompletion;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.app.ProviderModels.ResolvedRoute;
import dev.anveshan.byok.domain.ProviderPreset;

/**
 * Talks HTTP to one family of providers. Implementations throw the {@code Provider*Exception}
 * types for 429 / 401 / 404 / other failures so the router can react.
 */
public interface LlmTransport {

	ProviderPreset.Transport kind();

	RawCompletion complete(ResolvedRoute route, List<ChatMessage> messages, CompletionOptions options);

	List<ModelInfo> models(ResolvedConnection connection);

	/** A cheap call proving the key works (A-FR-4). Returns the model used, if any. */
	String test(ResolvedConnection connection, String model);

}
