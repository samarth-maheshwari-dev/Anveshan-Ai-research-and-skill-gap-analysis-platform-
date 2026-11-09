package dev.anveshan.identity.app;

import java.util.List;
import java.util.UUID;

/** Body of {@code GET /api/v1/me} (TECHSPEC §8.5). */
public record MeView(UUID id, String email, String name, String avatarUrl, String locale, String timezone,
		List<String> identities, Onboarding onboarding) {

	/** {@code hasRun} stays false until the research module exists (P09). */
	public record Onboarding(boolean hasVerifiedConnection, boolean hasRun) {
	}

}
