package dev.anveshan.report.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.report.app.ReportEditor;
import dev.anveshan.report.app.ReportEditor.PassageView;
import dev.anveshan.report.app.ReportEditor.Restored;
import dev.anveshan.report.app.ReportEditor.VersionPage;
import dev.anveshan.report.app.ReportQueries;
import dev.anveshan.report.app.ReportQueries.ReportView;
import dev.anveshan.report.app.ReportQueries.SectionView;
import dev.anveshan.shared.security.CurrentUser;
import dev.anveshan.shared.web.Etags;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Reports (TECHSPEC §8.5, Module F): read, the citation panel's passage, edit a section, version history and
 * restore. Someone else's report (or one that doesn't exist) → 404, never 403. Assist, regenerate and Find better
 * sources use the research engine and live in the research module.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

	/** The section as the editor holds it (TipTap JSON); the server keeps only the node types it allows. */
	record UpdateSectionRequest(@NotNull Map<String, Object> contentDoc) {
	}

	private final ReportQueries reports;
	private final ReportEditor editor;
	private final CurrentUser currentUser;

	ReportController(ReportQueries reports, ReportEditor editor, CurrentUser currentUser) {
		this.reports = reports;
		this.editor = editor;
		this.currentUser = currentUser;
	}

	@GetMapping("/{id}")
	ResponseEntity<ReportView> get(@PathVariable UUID id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(currentUser.id(), id));
	}

	/** F-FR-1: the chunk behind a citation and the exact quoted span to highlight. */
	@GetMapping("/{id}/claims/{claimId}/passage")
	ResponseEntity<PassageView> passage(@PathVariable UUID id, @PathVariable UUID claimId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(editor.passage(currentUser.id(), id, claimId));
	}

	/** Save a section (autosave or [Save]); a stale If-Match → 409 VERSION_CONFLICT with the current version. */
	@PatchMapping("/{id}/sections/{sectionId}")
	ResponseEntity<SectionView> updateSection(@PathVariable UUID id, @PathVariable UUID sectionId,
			@Parameter(description = "The section version the editor loaded (ETag)") @RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody UpdateSectionRequest req) {
		SectionView s = editor.save(currentUser.id(), id, sectionId, Etags.version(ifMatch), req.contentDoc());
		return ResponseEntity.ok().eTag(Etags.of(s.version())).body(s);
	}

	/** F-FR-6: every save is a version, newest first. */
	@GetMapping("/{id}/versions")
	ResponseEntity<VersionPage> versions(@PathVariable UUID id, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(editor.versions(currentUser.id(), id, cursor, limit));
	}

	/** Brings back an older version as a new one (history is kept). */
	@PostMapping("/{id}/versions/{version}/restore")
	Restored restore(@PathVariable UUID id, @PathVariable int version) {
		return editor.restore(currentUser.id(), id, version);
	}

}
