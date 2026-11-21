package dev.anveshan.llm.infra;

import java.time.Clock;

import dev.anveshan.llm.app.LlmTransport;
import dev.anveshan.llm.app.WebSearchTransport;
import tools.jackson.databind.json.JsonMapper;

/** Real transports (real HTTP, real error mapping) for unit tests outside this package. */
public final class TestTransports {

	private TestTransports() {
	}

	public static LlmTransport openAi(LlmProperties props, Clock clock) {
		return new OpenAiCompatibleTransport(new ProviderHttp(props, clock), JsonMapper.builder().build());
	}

	public static WebSearchTransport tavily(LlmProperties props, Clock clock) {
		return new TavilyTransport(new ProviderHttp(props, clock), JsonMapper.builder().build(), props);
	}

}
