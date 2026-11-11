package dev.anveshan.research.web;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.research.app.RunService;
import dev.anveshan.research.app.RunService.TemplateView;

/** Report templates for the New Research form (C-FR-5): public data, the same for everyone. */
@RestController
class TemplateController {

	record TemplateList(List<TemplateView> items) {
	}

	private final RunService runs;

	TemplateController(RunService runs) {
		this.runs = runs;
	}

	@GetMapping("/api/v1/templates")
	ResponseEntity<TemplateList> templates() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
				.body(new TemplateList(runs.templates()));
	}

}
