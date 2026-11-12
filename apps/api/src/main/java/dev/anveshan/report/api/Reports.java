package dev.anveshan.report.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds and stores a run's report (TECHSPEC §12.9): the writer's Markdown with {@code [c:<claimId>]} markers →
 * TipTap JSON with numbered citation nodes, a Conflicts section and a References list — all by code.
 */
public interface Reports {

	/** One section as written: Markdown citing claims as {@code [c:<claimId>]}. */
	record SectionDraft(String key, String title, String markdown) {
	}

	/** A CONFLICTING claim with the sources on each side and the model's one-line possible reason (may be null). */
	record ConflictDraft(UUID claimId, String text, List<UUID> supporting, List<UUID> contradicting, String reason) {
	}

	record AssembleInput(UUID runId, UUID ownerId, String title, String language, List<SectionDraft> sections,
			List<ConflictDraft> conflicts) {
	}

	record Assembled(UUID reportId, int sections, int citations, int sources) {
	}

	/** Creates or replaces the run's report. */
	Assembled assemble(AssembleInput input);

	Optional<UUID> reportIdForRun(UUID runId);

	/**
	 * T-14-01: the writer's {@code [c:C3]} markers → {@code [c:<claimId>]}; markers of claims not given to the writer
	 * are removed. {@code unmarked} counts factual-looking sentences without any marker (quality metric).
	 */
	MarkerCheck checkMarkers(String markdown, java.util.Map<String, UUID> aliases);

	record MarkerCheck(String markdown, int removed, int unmarked) {
	}

}
