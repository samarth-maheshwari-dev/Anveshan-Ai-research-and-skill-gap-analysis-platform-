package dev.anveshan.llm.app;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.anveshan.llm.api.AiAccess;
import dev.anveshan.llm.api.AiStatus;
import dev.anveshan.llm.api.DailyLimitException;
import dev.anveshan.llm.infra.LlmProperties;

/**
 * Gateway status and the per-user daily run allowance ({@code anveshan.llm.daily-runs-per-user}, UTC day). The
 * counter lives in {@code llm_daily_usage}; one upsert both checks and counts, so two quick starts can't both pass.
 */
@Service
class AiAccessService implements AiAccess {

	private final GatewayChain chain;
	private final JdbcClient jdbc;
	private final LlmProperties props;
	private final Clock clock;

	AiAccessService(GatewayChain chain, JdbcClient jdbc, LlmProperties props, Clock clock) {
		this.chain = chain;
		this.jdbc = jdbc;
		this.props = props;
		this.clock = clock;
	}

	@Override
	public boolean configured() {
		return chain.anyConfigured();
	}

	@Override
	public AiStatus status(UUID ownerId) {
		String state = !chain.anyConfigured() ? AiStatus.OFFLINE : chain.allOpen() ? AiStatus.BUSY : AiStatus.ONLINE;
		int used = jdbc.sql("SELECT coalesce(max(runs), 0) FROM llm_daily_usage WHERE owner_id = :o AND day = :d")
				.param("o", ownerId).param("d", today()).query(Integer.class).single();
		return new AiStatus(state, used, props.dailyRunsPerUser());
	}

	@Override
	public void consumeRun(UUID ownerId) {
		int limit = props.dailyRunsPerUser();
		int counted = jdbc.sql("""
				INSERT INTO llm_daily_usage (owner_id, day, runs) VALUES (:o, :d, 1)
				ON CONFLICT (owner_id, day) DO UPDATE SET runs = llm_daily_usage.runs + 1
				WHERE llm_daily_usage.runs < :limit
				""").param("o", ownerId).param("d", today()).param("limit", limit).update();
		if (counted == 0) {
			throw new DailyLimitException(limit);
		}
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

}
