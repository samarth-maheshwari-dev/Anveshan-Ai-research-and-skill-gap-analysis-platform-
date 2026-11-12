package dev.anveshan.report.web;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.report.app.ReportQueries;
import dev.anveshan.report.app.ReportQueries.ReportView;
import dev.anveshan.shared.security.CurrentUser;

/** Reports (TECHSPEC §8.5, Module F). Editing, passages and export arrive with the Report Studio (P15–P16). */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

	private final ReportQueries reports;
	private final CurrentUser currentUser;

	ReportController(ReportQueries reports, CurrentUser currentUser) {
		this.reports = reports;
		this.currentUser = currentUser;
	}

	/** Another user's report (or none) → 404, never 403. */
	@GetMapping("/{id}")
	ResponseEntity<ReportView> get(@PathVariable UUID id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(currentUser.id(), id));
	}

}
