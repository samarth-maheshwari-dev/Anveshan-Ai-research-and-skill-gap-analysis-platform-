package dev.anveshan.research.api;

import java.util.UUID;

/** D-FR-7: a plan waited too long for approval and its run was cancelled (notify module tells the user, P20). */
public record PlanExpired(UUID runId, UUID ownerId) {
}
