package dev.anveshan.byok.infra;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Learned usage and cooldowns per (connection, model, UTC day) in {@code route_quota} (TECHSPEC §10.3). */
@Component
public class QuotaTracker {

	private final JdbcClient jdbc;
	private final Clock clock;

	QuotaTracker(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	public void recordSuccess(UUID connectionId, String model, Integer tokensIn, Integer tokensOut,
			Integer remainingRequests, Integer remainingTokens) {
		jdbc.sql("""
				INSERT INTO route_quota (connection_id, model, day, requests, tokens_in, tokens_out,
				                         remaining_requests_hint, remaining_tokens_hint)
				VALUES (:c, :m, :d, 1, :ti, :to, :rr, :rt)
				ON CONFLICT (connection_id, model, day) DO UPDATE SET
				  requests = route_quota.requests + 1,
				  tokens_in = route_quota.tokens_in + EXCLUDED.tokens_in,
				  tokens_out = route_quota.tokens_out + EXCLUDED.tokens_out,
				  remaining_requests_hint = COALESCE(EXCLUDED.remaining_requests_hint, route_quota.remaining_requests_hint),
				  remaining_tokens_hint = COALESCE(EXCLUDED.remaining_tokens_hint, route_quota.remaining_tokens_hint)
				""")
				.param("c", connectionId).param("m", model).param("d", today())
				.param("ti", tokensIn == null ? 0 : tokensIn).param("to", tokensOut == null ? 0 : tokensOut)
				.param("rr", remainingRequests).param("rt", remainingTokens)
				.update();
	}

	/** Sets the cooldown, never shortening an existing one. */
	public void coolDown(UUID connectionId, String model, Instant until) {
		jdbc.sql("""
				INSERT INTO route_quota (connection_id, model, day, cooldown_until) VALUES (:c, :m, :d, :u)
				ON CONFLICT (connection_id, model, day) DO UPDATE SET
				  cooldown_until = GREATEST(COALESCE(route_quota.cooldown_until, EXCLUDED.cooldown_until), EXCLUDED.cooldown_until)
				""")
				.param("c", connectionId).param("m", model).param("d", today()).param("u", Timestamp.from(until))
				.update();
	}

	/** Active cooldown, looking at today and yesterday so a cooldown can cross UTC midnight. */
	public Optional<Instant> activeCooldown(UUID connectionId, String model) {
		Instant now = clock.instant();
		return jdbc.sql("""
				SELECT max(cooldown_until) FROM route_quota
				WHERE connection_id = :c AND model = :m AND day >= :since
				""")
				.param("c", connectionId).param("m", model).param("since", today().minusDays(1))
				.query(Timestamp.class).optional()
				.map(Timestamp::toInstant)
				.filter(until -> until.isAfter(now));
	}

}
