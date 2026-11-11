package dev.anveshan.research.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.anveshan.research.app.RunService;
import dev.anveshan.research.app.RunService.CreateCommand;
import dev.anveshan.research.app.RunService.Created;
import dev.anveshan.research.app.RunService.RunView;
import dev.anveshan.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Research runs (TECHSPEC §8.5): start, read, pause/resume/cancel, retry a failed step, live events (§8.6). */
@RestController
@RequestMapping("/api/v1/runs")
class RunController {

	/**
	 * The New Research form (PRD Module C). P09 stores the options as the run's config; P10 validates them against
	 * the form's allowed values and the templates.
	 */
	record CreateRunRequest(@NotBlank @Size(max = 2000) String topic, // 10–500 characters: checked in RunService (code points)
			@Size(max = 40) String objective,
			@Size(max = 40) String depth, @Size(max = 10) List<@Size(max = 40) String> sources,
			@Size(max = 40) String timeRange, @Size(max = 40) String outputStyle, @Size(max = 10) String language,
			@Size(max = 60) String templateCode, @Size(max = 20) List<UUID> attachmentIds,
			@Size(max = 40) String skillMode) {

		Map<String, Object> config() {
			Map<String, Object> c = new LinkedHashMap<>();
			put(c, "objective", objective);
			put(c, "depth", depth);
			put(c, "sources", sources);
			put(c, "timeRange", timeRange);
			put(c, "outputStyle", outputStyle);
			put(c, "language", language);
			put(c, "templateCode", templateCode);
			put(c, "attachmentIds", attachmentIds);
			put(c, "skillMode", skillMode);
			return c;
		}

		private static void put(Map<String, Object> c, String k, Object v) {
			if (v != null) {
				c.put(k, v);
			}
		}

	}

	private final RunService runs;
	private final CurrentUser currentUser;

	RunController(RunService runs, CurrentUser currentUser) {
		this.runs = runs;
		this.currentUser = currentUser;
	}

	/** 202 + Location; the same {@code Idempotency-Key} returns the same run instead of starting a second one. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED) // documents 202 in OpenAPI; the ResponseEntity sets it at runtime
	ResponseEntity<RunView> create(@Valid @RequestBody CreateRunRequest req,
			@Parameter(description = "UUID; the same key within 24 h returns the same run",
					schema = @Schema(type = "string", format = "uuid")) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		Created c = runs.create(currentUser.id(), new CreateCommand(req.topic(), req.config()), idempotencyKey);
		return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + c.run().id()))
				.eTag("\"" + c.run().version() + "\"").body(c.run());
	}

	@GetMapping("/{id}")
	ResponseEntity<RunView> get(@PathVariable UUID id) {
		RunView run = runs.get(currentUser.id(), id);
		return ResponseEntity.ok().eTag("\"" + run.version() + "\"").cacheControl(CacheControl.noStore()).body(run);
	}

	/** E-FR-2: takes effect after the step that is running now finishes. */
	@PostMapping("/{id}/pause")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void pause(@PathVariable UUID id) {
		runs.pause(currentUser.id(), id);
	}

	@PostMapping("/{id}/resume")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void resume(@PathVariable UUID id) {
		runs.resume(currentUser.id(), id);
	}

	@PostMapping("/{id}/cancel")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void cancel(@PathVariable UUID id) {
		runs.cancel(currentUser.id(), id);
	}

	/** E-FR-4: only a FAILED step; the run goes back to RUNNING. */
	@PostMapping("/{id}/steps/{stepId}/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void retryStep(@PathVariable UUID id, @PathVariable UUID stepId) {
		runs.retryStep(currentUser.id(), id, stepId);
	}

	/**
	 * E-FR-1: Server-Sent Events. A reconnecting browser sends {@code Last-Event-ID}; every event after it is
	 * replayed first, then live ones follow. Keep-alive comments every 15 s.
	 */
	@GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter events(@PathVariable UUID id,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, HttpServletResponse res) {
		res.setHeader("Cache-Control", "no-store");
		res.setHeader("X-Accel-Buffering", "no"); // tell proxies not to buffer the stream (TS-G10)
		return runs.events(currentUser.id(), id, parseLastId(lastEventId));
	}

	private static long parseLastId(String header) {
		if (header == null || header.isBlank()) {
			return 0;
		}
		try {
			return Math.max(0, Long.parseLong(header.strip()));
		}
		catch (NumberFormatException e) {
			return 0; // a garbled id just means "replay from the start"
		}
	}

}
