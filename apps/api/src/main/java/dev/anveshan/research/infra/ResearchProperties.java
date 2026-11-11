package dev.anveshan.research.infra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Run-engine tunables ({@code anveshan.research.*}, TECHSPEC §12.2). All required: a missing value fails
 * startup instead of silently using a code default (RULES §4.7).
 *
 * @param maxAttempts     a step that throws this many times is FAILED and fails the run (E-FR-4)
 * @param retryBackoff    wait before the 2nd attempt; each later attempt waits 4× longer
 * @param staleAfter      a RUNNING step older than this is treated as crashed and re-queued (AC-E1)
 * @param sweepInterval   how often stale steps and due auto-resumes are checked
 * @param sseTimeout      an SSE connection is closed after this; the browser reconnects with Last-Event-ID
 * @param sseKeepAlive    comment line sent to idle SSE connections so proxies keep them open
 * @param aiLimitFallback pause length when a provider gives no reset time with its 429
 * @param requeueInterval how often PENDING steps of active runs get a job again (a lost hand-off or a failed claim
 *                        never strands a step until the next restart)
 */
@ConfigurationProperties("anveshan.research")
public record ResearchProperties(int maxAttempts, Duration retryBackoff, Duration staleAfter, Duration sweepInterval,
		Duration sseTimeout, Duration sseKeepAlive, Duration aiLimitFallback, Duration requeueInterval) {

	public ResearchProperties {
		if (maxAttempts < 1 || retryBackoff == null || staleAfter == null || sweepInterval == null
				|| sseTimeout == null || sseKeepAlive == null || aiLimitFallback == null || requeueInterval == null) {
			throw new IllegalStateException("anveshan.research.* (max-attempts >= 1, retry-backoff, stale-after, "
					+ "sweep-interval, sse-timeout, sse-keep-alive, ai-limit-fallback, requeue-interval) are required");
		}
	}

	/** Wait before attempt {@code nextAttempt} (2, 3, …): backoff × 4^(nextAttempt − 2). */
	public Duration backoffBefore(int nextAttempt) {
		long factor = 1L << (2 * Math.max(0, nextAttempt - 2));
		return retryBackoff.multipliedBy(factor);
	}

}
