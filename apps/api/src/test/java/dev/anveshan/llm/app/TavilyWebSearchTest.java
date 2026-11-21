package dev.anveshan.llm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.llm.FakeGateway;
import dev.anveshan.llm.FakeGateway.Reply;
import dev.anveshan.llm.api.WebSearch.WebQuery;
import dev.anveshan.llm.api.WebSearch.WebSearchFailedException;
import dev.anveshan.llm.infra.LlmProperties;
import dev.anveshan.llm.infra.TestTransports;

/** Web search with the developer's Tavily key from the environment; users never supply one. */
class TavilyWebSearchTest {

	final FakeGateway fake = new FakeGateway();

	@org.junit.jupiter.api.AfterEach
	void closeFake() {
		fake.close();
	}

	@AfterEach
	void stop() {
		fake.close();
	}

	TavilyWebSearch search(String key) {
		var props = new LlmProperties(List.of(), 5, Duration.ofSeconds(5), null,
				new LlmProperties.Tavily(URI.create(fake.baseUrl("tavily")), key));
		return new TavilyWebSearch(TestTransports.tavily(props, Clock.systemUTC()));
	}

	@Test
	@DisplayName("T-16-11 platform Tavily key: blank → unavailable, never called; set → sent as Bearer, never in results or errors")
	void platformKey() {
		var q = new WebQuery("campus lost and found", 5, List.of(), List.of(), null);
		var off = search("");
		assertThat(off.available(UUID.randomUUID())).isFalse();
		assertThatThrownBy(() -> off.search(UUID.randomUUID(), q)).isInstanceOf(WebSearchFailedException.class);
		assertThat(fake.requestsTo("tavily")).isEmpty();

		fake.reply("tavily", new Reply(200, Map.of(),
				"{\"results\":[{\"title\":\"T\",\"url\":\"https://u.example/a\",\"content\":\"c\"}]}", 0));
		var on = search("tvly-secret");
		assertThat(on.available(UUID.randomUUID())).isTrue();
		var results = on.search(UUID.randomUUID(), q);
		assertThat(results).singleElement().satisfies(r -> assertThat(r.url()).isEqualTo("https://u.example/a"));
		assertThat(fake.requestsTo("tavily").getFirst().headers().get("authorization")).isEqualTo("Bearer tvly-secret");
		assertThat(results.toString()).doesNotContain("tvly-secret");

		fake.reply("tavily", Reply.status(401));
		assertThatThrownBy(() -> on.search(UUID.randomUUID(), q)).isInstanceOf(WebSearchFailedException.class)
				.message().doesNotContain("tvly-secret").doesNotContain("sk-secret");
	}

}
