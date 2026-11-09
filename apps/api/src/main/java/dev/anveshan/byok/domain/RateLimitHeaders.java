package dev.anveshan.byok.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns provider rate-limit headers into "when can we call again" (TECHSPEC §10.3). Pure: the
 * caller passes a header lookup and the current time.
 */
public final class RateLimitHeaders {

	static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);
	static final Duration MAX_WAIT = Duration.ofHours(24);

	private static final List<String> RESET_HEADERS = List.of("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens",
			"x-ratelimit-reset", "anthropic-ratelimit-requests-reset", "anthropic-ratelimit-tokens-reset",
			"anthropic-ratelimit-input-tokens-reset", "anthropic-ratelimit-output-tokens-reset");

	/** "2m59.56s", "1h2m3s", "6ms", "12s", "0.5s" */
	private static final Pattern GO_DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(h|ms|m|s)");
	private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

	private RateLimitHeaders() {
	}

	/** When the route may be used again. Takes the latest of all signals; never before now + 1 s. */
	public static Instant retryAt(Function<String, String> header, Instant now) {
		Optional<Instant> fromRetryAfter = Optional.ofNullable(header.apply("retry-after"))
				.flatMap(v -> parseRetryAfter(v.strip(), now));
		Instant result = fromRetryAfter.orElse(null);
		if (result == null) {
			for (String name : RESET_HEADERS) {
				String v = header.apply(name);
				if (v == null || v.isBlank()) {
					continue;
				}
				Optional<Instant> at = parseReset(v.strip(), now);
				if (at.isPresent() && (result == null || at.get().isAfter(result))) {
					result = at.get();
				}
			}
		}
		if (result == null) {
			result = now.plus(DEFAULT_WAIT);
		}
		if (result.isBefore(now.plusSeconds(1))) {
			result = now.plusSeconds(1);
		}
		Instant max = now.plus(MAX_WAIT);
		return result.isAfter(max) ? max : result;
	}

	/** {@code retry-after}: delay in seconds or an HTTP date (RFC 9110). */
	static Optional<Instant> parseRetryAfter(String v, Instant now) {
		if (NUMBER.matcher(v).matches()) {
			return Optional.of(now.plusMillis(new BigDecimal(v).movePointRight(3).longValue()));
		}
		try {
			return Optional.of(ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
		}
		catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}

	/** Reset hints: Go-style durations (OpenAI/Groq), RFC 3339 (Anthropic), epoch s/ms or seconds. */
	static Optional<Instant> parseReset(String v, Instant now) {
		if (NUMBER.matcher(v).matches()) {
			BigDecimal n = new BigDecimal(v);
			if (n.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0) {
				return Optional.of(Instant.ofEpochMilli(n.longValue())); // OpenRouter: epoch ms
			}
			if (n.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
				return Optional.of(Instant.ofEpochSecond(n.longValue()));
			}
			return Optional.of(now.plusMillis(n.movePointRight(3).longValue()));
		}
		Matcher m = GO_DURATION.matcher(v);
		long millis = 0;
		int consumed = 0;
		while (m.find()) {
			if (m.start() != consumed) {
				break;
			}
			BigDecimal amount = new BigDecimal(m.group(1));
			millis += switch (m.group(2)) {
				case "h" -> amount.multiply(BigDecimal.valueOf(3_600_000)).longValue();
				case "m" -> amount.multiply(BigDecimal.valueOf(60_000)).longValue();
				case "s" -> amount.multiply(BigDecimal.valueOf(1_000)).longValue();
				default -> amount.longValue(); // ms
			};
			consumed = m.end();
		}
		if (consumed == v.length() && consumed > 0) {
			return Optional.of(now.plusMillis(millis));
		}
		try {
			return Optional.of(OffsetDateTime.parse(v).toInstant());
		}
		catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}

	/** Remaining requests/tokens hints, when a provider sends them. */
	public static Integer remainingRequests(Function<String, String> header) {
		return firstInt(header, "x-ratelimit-remaining-requests", "anthropic-ratelimit-requests-remaining",
				"x-ratelimit-remaining");
	}

	public static Integer remainingTokens(Function<String, String> header) {
		return firstInt(header, "x-ratelimit-remaining-tokens", "anthropic-ratelimit-tokens-remaining");
	}

	private static Integer firstInt(Function<String, String> header, String... names) {
		for (String n : names) {
			String v = header.apply(n);
			if (v != null && v.strip().matches("\\d{1,9}")) {
				return Integer.parseInt(v.strip());
			}
		}
		return null;
	}

}
