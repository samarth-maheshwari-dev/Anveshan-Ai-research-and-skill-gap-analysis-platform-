package dev.anveshan.llm.app;

import java.util.List;

import dev.anveshan.llm.api.WebSearch.WebQuery;
import dev.anveshan.llm.api.WebSearch.WebResult;

/** The platform web search call (implemented in infra). Throws {@code ProviderException} subtypes on errors. */
public interface WebSearchTransport {

	/** The developer configured a search key. */
	boolean configured();

	List<WebResult> search(WebQuery query);

}
