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

	/** A NO_SOURCE claim: found in a source, but the check couldn't confirm it. Listed as an open question. */
	record OpenQuestionDraft(UUID claimId, String text) {
	}

	record AssembleInput(UUID runId, UUID ownerId, String title, String language, List<SectionDraft> sections,
			List<ConflictDraft> conflicts, List<OpenQuestionDraft> openQuestions) {

		public AssembleInput {
			openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
		}

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

	// ------------------------------------------------------------------ Report Studio (P15)

	/** A report and the run it was written from. */
	record ReportRef(UUID id, UUID runId, UUID ownerId, String language) {
	}

	/** The owner's report, or empty (another user's report is simply not found). */
	Optional<ReportRef> owned(UUID ownerId, UUID reportId);

	/** {@code editable} is false for the sections built by code (Conflicts, References). */
	record SectionRef(UUID id, String key, String title, int version, boolean editable) {
	}

	Optional<SectionRef> section(UUID reportId, UUID sectionId);

	/** Selected text with {@code [c:<claimId>]} markers for the citations inside it. */
	record SelectedText(String text, List<UUID> claimIds) {
	}

	/**
	 * The text between two editor positions of a section (Assist on a selection, F-FR-4).
	 *
	 * @throws IllegalArgumentException with a message for the user (empty selection)
	 */
	SelectedText select(UUID reportId, UUID sectionId, int from, int to);

	record SavedSection(UUID id, String key, String title, java.util.Map<String, Object> contentDoc, int version) {
	}

	/**
	 * Regenerate (F-FR-7): Markdown with {@code [c:<claimId>]} markers replaces the section — at
	 * {@code expectedVersion} when given (else 409) — and the report is renumbered and saved as a new version.
	 */
	SavedSection replaceSection(UUID ownerId, UUID reportId, UUID sectionId, Integer expectedVersion, String markdown);

	/** An Assist suggestion as a TipTap fragment, citations numbered like the report (claims of its run only). */
	java.util.Map<String, Object> fragment(UUID reportId, String markdown);

	/** A claim's label changed (Find better sources): the report's citation labels follow. */
	void refreshLabels(UUID reportId);

	// ------------------------------------------------------------------ Export (P16b)

	/** One section as Markdown, citations as {@code [n]}. */
	record ExportSection(String title, String markdown) {
	}

	/** The report as the owner sees it now, ready for Markdown / PDF export. */
	record ExportDoc(String title, String language, String citationStyle, java.time.Instant createdAt,
			List<ExportSection> sections) {
	}

	/** The owner's report for export, or empty (another user's report is simply not found). */
	Optional<ExportDoc> exportDoc(UUID ownerId, UUID reportId);

}
