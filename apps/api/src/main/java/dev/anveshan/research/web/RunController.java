package dev.anveshan.research.web;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.anveshan.research.app.PlanService;
import dev.anveshan.research.app.PlanService.PlanEdit;
import dev.anveshan.research.app.RunConfig;
import dev.anveshan.research.app.RunService;
import dev.anveshan.research.app.RunService.CreateCommand;
import dev.anveshan.research.app.RunService.Created;
import dev.anveshan.research.app.RunService.RunView;
import dev.anveshan.research.domain.RunOptions.Depth;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.domain.RunOptions.Objective;
import dev.anveshan.research.domain.RunOptions.OutputStyle;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.domain.RunOptions.TimeRange;
import dev.anveshan.shared.security.CurrentUser;
import dev.anveshan.shared.web.ApiException;
import io.github.bucket4j.Bucket;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Research runs (TECHSPEC §8.5): start, read, pause/resume/cancel, retry a failed step, live events (§8.6). */
@RestController
@RequestMapping("/api/v1/runs")
class RunController {

	/**
	 * The New Research form (PRD Module C). Unknown option values are rejected by JSON binding (400); missing ones
	 * come from the template, then the PRD defaults. Attachments (P12) and skill mode (P21) arrive later.
	 */
	record CreateRunRequest(@NotBlank @Size(max = 2000) String topic, // 10–500 characters: checked in RunService
			Objective objective, Depth depth, @Size(max = 5) List<SourceType> sources, TimeRange timeRange,
			OutputStyle outputStyle, Language language, @Size(max = 60) String templateCode) {

		RunConfig options() {
			return new RunConfig(objective, depth, sources, timeRange, outputStyle, language,
					templateCode == null || templateCode.isBlank() ? null : templateCode.strip());
		}

	}

	/** D-FR-3: the whole plan in the user's order (edited, added, deleted cards). */
	record PlanRequest(@NotNull @Size(max = 20) List<@Valid PlanCard> subQuestions) {
	}

	record PlanCard(UUID id, @Size(max = 2000) String text, @Size(max = 5) List<SourceType> sourceTypes,
			@Size(max = 10) List<@Size(max = 500) String> queries) {
	}

	private static final int STARTS_PER_HOUR = 10;

	private final RunService runs;
	private final PlanService plans;
	private final CurrentUser currentUser;
	private final Map<UUID, Bucket> startBuckets = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Bucket> eldest) {
			return size() > 10_000;
		}
	});

	RunController(RunService runs, PlanService plans, CurrentUser currentUser) {
		this.runs = runs;
		this.plans = plans;
		this.currentUser = currentUser;
	}

	/** 202 + Location; the same {@code Idempotency-Key} returns the same run instead of starting a second one. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED) // documents 202 in OpenAPI; the ResponseEntity sets it at runtime
	ResponseEntity<RunView> create(@Valid @RequestBody CreateRunRequest req,
			@Parameter(description = "UUID; the same key within 24 h returns the same run",
					schema = @Schema(type = "string", format = "uuid")) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		UUID me = currentUser.id();
		// TECHSPEC §5.9: 10 research starts per user per hour (each one spends the user's own AI quota)
		Bucket bucket = startBuckets.computeIfAbsent(me, k -> Bucket.builder()
				.addLimit(l -> l.capacity(STARTS_PER_HOUR).refillGreedy(STARTS_PER_HOUR, Duration.ofHours(1))).build());
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many new researches",
					"You can start " + STARTS_PER_HOUR + " researches per hour. Try again a little later.");
		}
		Created c = runs.create(me, new CreateCommand(req.topic(), req.options()), idempotencyKey);
		return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + c.run().id()))
				.eTag("\"" + c.run().version() + "\"").body(c.run());
	}

	@GetMapping("/{id}")
	ResponseEntity<RunView> get(@PathVariable UUID id) {
		RunView run = runs.get(currentUser.id(), id);
		return ResponseEntity.ok().eTag("\"" + run.version() + "\"").cacheControl(CacheControl.noStore()).body(run);
	}

	/** D-FR-3/4: replace the plan while it awaits approval; the response carries the new estimate and ETag. */
	@PutMapping("/{id}/plan")
	ResponseEntity<RunView> updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanRequest req,
			@Parameter(description = "The run version the user saw (ETag)") @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		UUID me = currentUser.id();
		plans.updatePlan(me, id, version(ifMatch),
				req.subQuestions().stream().map(c -> new PlanEdit(c.id(), c.text(), c.sourceTypes(), c.queries())).toList());
		RunView run = runs.get(me, id);
		return ResponseEntity.ok().eTag("\"" + run.version() + "\"").body(run);
	}

	/** D-FR-5: approve the plan the user saw → RUNNING; the live room follows (S-07). */
	@PostMapping("/{id}/approve")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void approve(@PathVariable UUID id,
			@Parameter(description = "The run version the user saw (ETag)") @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		plans.approve(currentUser.id(), id, version(ifMatch));
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

	/** {@code If-Match: "3"} (or W/"3") → 3; anything else → null (the service answers 400). */
	private static Integer version(String ifMatch) {
		if (ifMatch == null) {
			return null;
		}
		String v = ifMatch.strip();
		if (v.startsWith("W/")) {
			v = v.substring(2);
		}
		v = v.replace("\"", "");
		try {
			return Integer.valueOf(v);
		}
		catch (NumberFormatException e) {
			return null;
		}
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
