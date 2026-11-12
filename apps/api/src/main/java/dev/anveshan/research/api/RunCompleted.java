package dev.anveshan.research.api;

import java.util.UUID;

/** A run finished with a report (knowledge mining P19, notifications P20 listen). Published after the status change. */
public record RunCompleted(UUID runId, UUID ownerId) {
}
