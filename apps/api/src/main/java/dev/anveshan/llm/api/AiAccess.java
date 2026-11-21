package dev.anveshan.llm.api;

import java.util.UUID;

/** Gateway availability and the per-user daily run allowance, for other modules (never endpoint keys). */
public interface AiAccess {

	/** At least one endpoint is configured (it may still be busy: a busy gateway pauses runs, it doesn't reject them). */
	boolean configured();

	AiStatus status(UUID ownerId);

	/**
	 * Counts one research run against today's allowance, atomically.
	 *
	 * @throws DailyLimitException the user already started {@code daily-runs-per-user} runs today
	 */
	void consumeRun(UUID ownerId);

}
