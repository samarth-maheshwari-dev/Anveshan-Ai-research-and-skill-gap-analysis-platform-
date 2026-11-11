package dev.anveshan.research.app;

import java.time.Instant;
import java.util.UUID;

/**
 * Hands step ids to background workers (JobRunr in production). Inside a transaction the hand-off waits for the
 * commit — otherwise a worker could look for a step that isn't visible yet and silently skip it.
 */
public interface StepQueue {

	void enqueue(UUID stepId);

	void enqueueAt(UUID stepId, Instant at);

}
