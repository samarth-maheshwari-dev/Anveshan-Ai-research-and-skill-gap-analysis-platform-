package dev.anveshan.export.web;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.export.app.ReportExporter;
import dev.anveshan.export.app.ReportExporter.ExportFile;
import dev.anveshan.export.app.ReportExporter.Format;
import dev.anveshan.shared.security.CurrentUser;
import dev.anveshan.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * Download a report as Markdown or PDF (P16b, F-FR-8). Owner only: someone else's report → 404.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ExportController {

	private final ReportExporter exporter;
	private final CurrentUser currentUser;

	ExportController(ReportExporter exporter, CurrentUser currentUser) {
		this.exporter = exporter;
		this.currentUser = currentUser;
	}

	@GetMapping("/{id}/export")
	ResponseEntity<byte[]> export(@PathVariable UUID id,
			@Parameter(description = "md or pdf") @RequestParam(defaultValue = "pdf") String format) {
		Format f = switch (format.toLowerCase(Locale.ROOT)) {
			case "md", "markdown" -> Format.MD;
			case "pdf" -> Format.PDF;
			default -> throw ApiException.badRequest("format must be md or pdf");
		};
		ExportFile file = exporter.export(currentUser.id(), id, f);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.filename()).build().toString())
				.contentType(MediaType.parseMediaType(file.contentType()))
				.body(file.body());
	}

}
