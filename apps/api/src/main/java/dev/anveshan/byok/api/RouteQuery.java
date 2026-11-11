package dev.anveshan.byok.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only facts about a user's task routing for other modules: which slots can run, and the first usable
 * route of each with what it has used today (Plan Review estimate, PRD §15.6). Never key material.
 */
public interface RouteQuery {

	/** Slots that have at least one route to a VERIFIED connection (C-FR-3: PLAN and WRITE must be here). */
	Set<TaskSlot> readySlots(UUID ownerId);

	/**
	 * For each slot, its first route to a VERIFIED connection, with today's learned usage.
	 *
	 * @return one entry per routed slot, in {@link TaskSlot} order
	 */
	List<FirstRoute> firstRoutes(UUID ownerId);

	/**
	 * @param usedToday       requests sent on this connection + model today (UTC)
	 * @param remainingToday  the provider's last "remaining requests" header, if it ever sent one; otherwise
	 *                        unknown (null) — we don't guess free-tier limits
	 */
	record FirstRoute(TaskSlot slot, UUID connectionId, String connectionLabel, String provider, String model,
			int usedToday, Integer remainingToday) {
	}

}
