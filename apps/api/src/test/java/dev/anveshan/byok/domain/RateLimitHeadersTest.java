package dev.anveshan.byok.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimitHeadersTest {

	static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

	static Instant retryAt(Map<String, String> headers) {
		return RateLimitHeaders.retryAt(headers::get, NOW);
	}

	@Test
	@DisplayName("T-07-09 retry-after: 12 → now + 12 s")
	void retryAfterSeconds() {
		assertThat(retryAt(Map.of("retry-after", "12"))).isEqualTo(NOW.plusSeconds(12));
		assertThat(retryAt(Map.of("retry-after", "1.5"))).isEqualTo(NOW.plusMillis(1500));
	}

	@Test
	@DisplayName("T-07-09 retry-after as an HTTP date")
	void retryAfterHttpDate() {
		assertThat(retryAt(Map.of("retry-after", "Mon, 09 Nov 2026 10:05:00 GMT")))
				.isEqualTo(Instant.parse("2026-11-09T10:05:00Z"));
	}

	@Test
	@DisplayName("T-07-09 x-ratelimit-reset-requests: 2m59.56s → now + 179.56 s; other Go durations")
	void goDurations() {
		assertThat(retryAt(Map.of("x-ratelimit-reset-requests", "2m59.56s"))).isEqualTo(NOW.plusMillis(179_560));
		assertThat(retryAt(Map.of("x-ratelimit-reset-tokens", "1h2m3s"))).isEqualTo(NOW.plusSeconds(3723));
		assertThat(retryAt(Map.of("x-ratelimit-reset-requests", "6ms"))).as("never sooner than 1 s")
				.isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	@DisplayName("T-07-09 the latest of several reset hints wins; retry-after takes precedence")
	void precedence() {
		assertThat(retryAt(Map.of("x-ratelimit-reset-requests", "10s", "x-ratelimit-reset-tokens", "40s")))
				.isEqualTo(NOW.plusSeconds(40));
		assertThat(retryAt(Map.of("retry-after", "5", "x-ratelimit-reset-tokens", "40s"))).isEqualTo(NOW.plusSeconds(5));
	}

	@Test
	@DisplayName("T-07-09 Anthropic RFC 3339 reset, OpenRouter epoch-millis reset")
	void otherFormats() {
		assertThat(retryAt(Map.of("anthropic-ratelimit-requests-reset", "2026-11-09T10:01:30Z")))
				.isEqualTo(Instant.parse("2026-11-09T10:01:30Z"));
		long epochMs = NOW.plusSeconds(20).toEpochMilli();
		assertThat(retryAt(Map.of("x-ratelimit-reset", String.valueOf(epochMs)))).isEqualTo(NOW.plusSeconds(20));
	}

	@Test
	@DisplayName("T-07-09 no or garbage headers → conservative 60 s; absurd values capped at 24 h")
	void defaultsAndCaps() {
		assertThat(retryAt(Map.of())).isEqualTo(NOW.plusSeconds(60));
		assertThat(retryAt(Map.of("retry-after", "soon"))).isEqualTo(NOW.plusSeconds(60));
		assertThat(retryAt(Map.of("retry-after", "999999999"))).isEqualTo(NOW.plusSeconds(24 * 3600));
	}

	@Test
	@DisplayName("T-07-09 remaining-requests / remaining-tokens hints")
	void remainingHints() {
		Map<String, String> h = Map.of("x-ratelimit-remaining-requests", "14", "x-ratelimit-remaining-tokens", "5900");
		assertThat(RateLimitHeaders.remainingRequests(h::get)).isEqualTo(14);
		assertThat(RateLimitHeaders.remainingTokens(h::get)).isEqualTo(5900);
		assertThat(RateLimitHeaders.remainingRequests(Map.<String, String>of()::get)).isNull();
	}

}
