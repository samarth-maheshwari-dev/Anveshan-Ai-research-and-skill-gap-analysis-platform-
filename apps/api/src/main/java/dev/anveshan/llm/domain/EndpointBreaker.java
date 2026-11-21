package dev.anveshan.llm.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A simple circuit breaker for one gateway endpoint: after {@code threshold} failures in a row it stays open for
 * {@code openFor}, then lets calls through again (one more failure re-opens it). Pure: the caller passes the time.
 * Not thread-safe on its own; the owner synchronises.
 */
public final class EndpointBreaker {

	private final int threshold;
	private final Duration openFor;
	private int failures;
	private Instant openUntil;

	public EndpointBreaker(int threshold, Duration openFor) {
		this.threshold = threshold;
		this.openFor = openFor;
	}

	public boolean isOpen(Instant now) {
		return openUntil != null && now.isBefore(openUntil);
	}

	/** When an open breaker lets calls through again; empty while closed. */
	public Optional<Instant> openUntil(Instant now) {
		return isOpen(now) ? Optional.of(openUntil) : Optional.empty();
	}

	public void success() {
		failures = 0;
		openUntil = null;
	}

	public void failure(Instant now) {
		if (openUntil != null && !now.isBefore(openUntil)) {
			failures = threshold - 1; // half-open: one failure after the pause re-opens it
		}
		failures++;
		if (failures >= threshold) {
			openUntil = now.plus(openFor);
		}
	}

}
