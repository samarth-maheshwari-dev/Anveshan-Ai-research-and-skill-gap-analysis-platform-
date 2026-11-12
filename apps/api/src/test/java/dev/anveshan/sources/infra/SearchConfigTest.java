package dev.anveshan.sources.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;

class SearchConfigTest {

	/** The real application.yml, bound the way Spring Boot binds it. */
	static SearchProperties fromApplicationYml() throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
				.forEach(env.getPropertySources()::addLast);
		return Binder.get(env).bind("anveshan.search", SearchProperties.class).get();
	}

	@Test
	@DisplayName("T-11-08 rate limits from application.yml: S2 ≤ 1/s, arXiv ≤ 1 per 3 s, Crossref ≤ 3/s")
	void rateLimits() throws Exception {
		SearchResilience r = new SearchResilience(fromApplicationYml());
		assertLimit(r.rateLimiterConfig("semanticscholar"), 1, Duration.ofSeconds(1));
		assertLimit(r.rateLimiterConfig("arxiv"), 1, Duration.ofSeconds(3));
		assertLimit(r.rateLimiterConfig("crossref"), 3, Duration.ofSeconds(1));
		assertLimit(r.rateLimiterConfig("openalex"), 5, Duration.ofSeconds(1));
		assertLimit(r.rateLimiterConfig("github"), 10, Duration.ofSeconds(1));
	}

	@Test
	@DisplayName("T-11-08 no personal data in the User-Agent; SearXNG off without an instance")
	void defaults() throws Exception {
		SearchProperties p = fromApplicationYml();
		assertThat(p.userAgent()).startsWith("Anveshan/").doesNotContain("@").doesNotContainIgnoringCase("samarth");
		assertThat(p.api("searxng").usable()).isFalse();
		assertThat(p.api("openalex").usable()).isTrue();
		assertThat(p.api("nonexistent").usable()).isFalse();
	}

	@Test
	@DisplayName("T-11-08 daily budget: an API at its limit is refused until the next UTC day")
	void budget() {
		SearchProperties p = new SearchProperties("UA", "", 10, 2, Duration.ofHours(1), Duration.ofSeconds(1),
				Duration.ofSeconds(45), Map.of("openalex",
						new SearchProperties.Api(true, "https://x/", "", 5, Duration.ofSeconds(1), Duration.ofSeconds(1), 2)));
		Clock day1 = Clock.fixed(Instant.parse("2026-11-12T23:59:00Z"), ZoneOffset.UTC);
		ExternalBudget b = new ExternalBudget(p, day1);
		assertThat(b.tryUse("openalex")).isTrue();
		assertThat(b.tryUse("openalex")).isTrue();
		assertThat(b.tryUse("openalex")).isFalse();
		assertThat(b.tryUse("crossref")).as("no budget configured = unlimited").isTrue();
		ExternalBudget next = new ExternalBudget(p, Clock.fixed(Instant.parse("2026-11-13T00:01:00Z"), ZoneOffset.UTC));
		assertThat(next.tryUse("openalex")).isTrue();
	}

	@Test
	@DisplayName("T-11-04 arXiv XML with a DOCTYPE (XXE) is refused, not parsed")
	void arxivRefusesDoctype() {
		String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
				+ "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>&e;</title><id>x</id></entry></feed>";
		assertThatThrownBy(() -> ArxivAdapter.parse(evil)).isInstanceOf(SearchHttp.SearchApiException.class);
	}

	private static void assertLimit(RateLimiterConfig c, int permits, Duration period) {
		assertThat(c.getLimitForPeriod()).isEqualTo(permits);
		assertThat(c.getLimitRefreshPeriod()).isEqualTo(period);
	}

}
