package dev.anveshan.byok.api;

import java.util.UUID;

/** Read-only facts about a user's AI connections for other modules (never key material). */
public interface ConnectionQuery {

	boolean hasVerifiedConnection(UUID ownerId);

}
