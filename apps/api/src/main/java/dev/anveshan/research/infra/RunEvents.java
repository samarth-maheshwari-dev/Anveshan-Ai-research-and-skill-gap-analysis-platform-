package dev.anveshan.research.infra;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

/**
 * Emits a run event the way TECHSPEC §8.6 requires: written to {@code run_event} in the caller's transaction
 * (same tx as the change it describes), pushed to SSE clients only after that transaction commits — a rolled-back
 * change never reaches the browser.
 */
@Component
public class RunEvents {

	private final RunEventStore store;
	private final SseHub hub;
	private final JsonMapper json;
	private final Clock clock;

	RunEvents(RunEventStore store, SseHub hub, JsonMapper json, Clock clock) {
		this.store = store;
		this.hub = hub;
		this.json = json;
		this.clock = clock;
	}

	/** Payloads carry ids, statuses and counts — never prompts, provider bodies or keys. */
	public long emit(UUID runId, String type, Map<String, ?> payload) {
		long id = store.append(runId, type, json.writeValueAsString(payload), clock.instant());
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					hub.poke(runId);
				}
			});
		}
		else {
			hub.poke(runId);
		}
		return id;
	}

}
