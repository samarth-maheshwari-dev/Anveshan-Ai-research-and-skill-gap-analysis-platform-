package dev.anveshan.sources.infra;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Daily request budget per platform-keyed API (TECHSPEC §11.3, e.g. OpenAlex's ~$1/day). At the limit the API is
 * skipped until the next UTC day. In memory: a restart resets the count, which errs on the side of the free tier.
 */
@Component
public class ExternalBudget {

	private record Day(LocalDate date, AtomicInteger used) {
	}

	private final SearchProperties props;
	private final Clock clock;
	private final Map<String, Day> days = new ConcurrentHashMap<>();

	ExternalBudget(SearchProperties props, Clock clock) {
		this.props = props;
		this.clock = clock;
	}

	/** Takes one request from today's budget; false when it is used up. */
	public boolean tryUse(String api) {
		int budget = props.api(api).dailyBudget();
		if (budget <= 0) {
			return true;
		}
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		Day day = days.compute(api, (k, d) -> d == null || !d.date().equals(today) ? new Day(today, new AtomicInteger()) : d);
		int n = day.used().incrementAndGet();
		if (n > budget) {
			day.used().decrementAndGet();
			return false;
		}
		return true;
	}

	public int usedToday(String api) {
		Day d = days.get(api);
		return d == null || !d.date().equals(LocalDate.now(clock.withZone(ZoneOffset.UTC))) ? 0 : d.used().get();
	}

}
