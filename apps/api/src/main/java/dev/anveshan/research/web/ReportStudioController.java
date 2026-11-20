package dev.anveshan.research.web;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.report.api.Reports.SavedSection;
import dev.anveshan.research.app.ReportStudio;
import dev.anveshan.research.app.ReportStudio.AssistAction;
import dev.anveshan.shared.security.CurrentUser;
import dev.anveshan.shared.web.ApiException;
import dev.anveshan.shared.web.Etags;
import io.github.bucket4j.Bucket;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Report Studio actions that use the AI or the research engine (TECHSPEC §8.5, §12.10): Assist on a selection,
 * regenerate one section, Find better sources for one claim. The report must be the caller's (404 otherwise).
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportStudioController {

	/** Editor positions of the selection ({@code from} < {@code to}). */
	record Selection(@NotNull @Min(0) @Max(10_000_000) Integer from, @NotNull @Min(1) @Max(10_000_000) Integer to) {
	}

	record AssistRequest(@NotNull UUID sectionId, @NotNull @Valid Selection selection, @NotNull AssistAction action) {
	}

	/** A TipTap fragment the user accepts or discards in the editor. */
	record AssistResponse(Map<String, Object> suggestion) {
	}

	record RefreshStarted(UUID runId) {
	}

	/** TECHSPEC §5.9: Assist and Ask together, 60 per user per hour (each call costs the user's own quota). */
	private static final int ASSISTS_PER_HOUR = 60;

	private final ReportStudio studio;
	private final CurrentUser currentUser;
	private final Map<UUID, Bucket> buckets = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Bucket> eldest) {
			return size() > 10_000;
		}
	});

	ReportStudioController(ReportStudio studio, CurrentUser currentUser) {
		this.studio = studio;
		this.currentUser = currentUser;
	}

	/** F-FR-4: Simplify / More detail / Translate to Hindi on a selection. */
	@PostMapping("/{id}/assist")
	AssistResponse assist(@PathVariable UUID id, @Valid @RequestBody AssistRequest req) {
		UUID me = currentUser.id();
		Bucket bucket = buckets.computeIfAbsent(me, k -> Bucket.builder()
				.addLimit(l -> l.capacity(ASSISTS_PER_HOUR).refillGreedy(ASSISTS_PER_HOUR, Duration.ofHours(1))).build());
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests",
					"You can use Assist " + ASSISTS_PER_HOUR + " times per hour.");
		}
		return new AssistResponse(studio.assist(me, id, req.sectionId(), req.selection().from(), req.selection().to(),
				req.action()));
	}

	/** F-FR-7: rewrite one section from its existing verified claims (no new search); a new version. */
	@PostMapping("/{id}/sections/{sectionId}/regenerate")
	ResponseEntity<SavedSection> regenerate(@PathVariable UUID id, @PathVariable UUID sectionId,
			@Parameter(description = "The section version the editor loaded (ETag)") @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		SavedSection s = studio.regenerate(currentUser.id(), id, sectionId, Etags.version(ifMatch));
		return ResponseEntity.ok().eTag(Etags.of(s.version())).body(s);
	}

	/** F-FR-5: a mini-research for one claim; follow it on {@code GET /runs/{runId}} and its events. */
	@PostMapping("/{id}/claims/{claimId}/find-better-sources")
	ResponseEntity<RefreshStarted> findBetterSources(@PathVariable UUID id, @PathVariable UUID claimId) {
		UUID run = studio.findBetterSources(currentUser.id(), id, claimId);
		return ResponseEntity.accepted().location(URI.create("/api/v1/runs/" + run)).body(new RefreshStarted(run));
	}

}
