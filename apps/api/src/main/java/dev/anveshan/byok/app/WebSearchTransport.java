package dev.anveshan.byok.app;

import java.util.List;

import dev.anveshan.byok.api.WebSearch.WebQuery;
import dev.anveshan.byok.api.WebSearch.WebResult;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;

/** A search provider's HTTP call (implemented in infra). Throws {@code ProviderException} subtypes on errors. */
public interface WebSearchTransport {

	List<WebResult> search(ResolvedConnection connection, WebQuery query);

}
