package dev.anveshan.sources.infra;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Per-API rate limiter, retry and circuit breaker (TECHSPEC §11.3), built from {@link SearchProperties}. Order per
 * call: circuit breaker → retry → rate limiter (every attempt waits for its own permit, so retries stay polite).
 */
@Component
public class SearchResilience {

	/** A 5xx, 429 or network failure: worth another attempt. */
	public static class UpstreamException extends RuntimeException {

		public UpstreamException(String message) {
			super(message);
		}

	}

	private final SearchProperties props;
	private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
	private final Map<String, Retry> retries = new ConcurrentHashMap<>();
	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

	SearchResilience(SearchProperties props) {
		this.props = props;
	}

	/**
	 * Runs {@code call} for {@code api}. Throws {@link CallNotPermittedException} while the API's circuit is open,
	 * {@code RequestNotPermitted} when no permit came within the timeout, or the call's last exception.
	 */
	public <T> T call(String api, Supplier<T> call) {
		Supplier<T> limited = RateLimiter.decorateSupplier(limiter(api), call);
		Supplier<T> retried = Retry.decorateSupplier(retry(api), limited);
		return CircuitBreaker.decorateSupplier(breaker(api), retried).get();
	}

	/** Exposed for the configuration test (T-11-08). */
	public RateLimiterConfig rateLimiterConfig(String api) {
		return limiter(api).getRateLimiterConfig();
	}

	/** Forgets every limiter, retry and circuit state (tests: each starts with closed circuits). */
	public void reset() {
		limiters.clear();
		retries.clear();
		breakers.clear();
	}

	public boolean circuitOpen(String api) {
		return breaker(api).getState() == CircuitBreaker.State.OPEN;
	}

	private RateLimiter limiter(String api) {
		return limiters.computeIfAbsent(api, name -> {
			SearchProperties.Api a = props.api(name);
			return RateLimiter.of(name, RateLimiterConfig.custom().limitForPeriod(a.limitForPeriod())
					.limitRefreshPeriod(a.limitRefreshPeriod()).timeoutDuration(a.timeout()).build());
		});
	}

	private Retry retry(String api) {
		return retries.computeIfAbsent(api, name -> Retry.of(name, RetryConfig.custom().maxAttempts(3)
				.intervalFunction(IntervalFunction.ofExponentialBackoff(props.retryWait(), 2))
				.retryExceptions(IOException.class, UpstreamException.class).build()));
	}

	private CircuitBreaker breaker(String api) {
		return breakers.computeIfAbsent(api, name -> CircuitBreaker.of(name, CircuitBreakerConfig.custom()
				.slidingWindowSize(20).minimumNumberOfCalls(10).failureRateThreshold(50)
				.waitDurationInOpenState(Duration.ofSeconds(60)).build()));
	}

}
