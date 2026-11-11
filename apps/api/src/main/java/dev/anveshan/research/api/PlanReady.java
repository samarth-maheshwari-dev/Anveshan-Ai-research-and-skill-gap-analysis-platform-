package dev.anveshan.research.api;

import java.util.UUID;

/**
 * A run's plan is ready for the user's approval (PLANNING → AWAITING_APPROVAL, Module D). Published in the same
 * transaction as the status change; listeners (e.g. notifications, P20) run after commit. Ids only.
 */
public record PlanReady(UUID runId, UUID ownerId, int subQuestions) {
}
