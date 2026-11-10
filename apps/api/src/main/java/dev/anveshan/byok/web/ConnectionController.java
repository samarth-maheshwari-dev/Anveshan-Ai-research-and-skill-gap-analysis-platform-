package dev.anveshan.byok.web;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.byok.app.ConnectionService;
import dev.anveshan.byok.app.ConnectionService.ConnectionView;
import dev.anveshan.byok.app.ConnectionService.CreateCommand;
import dev.anveshan.byok.app.ConnectionService.TestResult;
import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.shared.security.CurrentUser;
import dev.anveshan.shared.web.ApiException;
import io.github.bucket4j.Bucket;

/** BYOK connections (TECHSPEC §8.5). Every call is scoped to the signed-in user. */
@RestController
@RequestMapping("/api/v1/connections")
class ConnectionController {

	/** The key arrives here once and is never echoed back. {@code toString} hides it. */
	record CreateRequest(ConnectionKind kind, AiProvider provider, String label, String apiKey, String baseUrl) {

		@Override
		public String toString() {
			return "CreateRequest[kind=" + kind + ", provider=" + provider + ", label=" + label + ", apiKey=****]";
		}

	}

	record TestRequest(String model) {
	}

	record ModelList(List<ModelInfo> items) {
	}

	record ConnectionList(List<ConnectionView> items) {
	}

	private static final int TESTS_PER_HOUR = 20;

	private final ConnectionService connections;
	private final CurrentUser currentUser;
	private final Map<UUID, Bucket> testBuckets = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Bucket> eldest) {
			return size() > 10_000;
		}
	});

	ConnectionController(ConnectionService connections, CurrentUser currentUser) {
		this.connections = connections;
		this.currentUser = currentUser;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ConnectionView create(@RequestBody CreateRequest req) {
		return connections.create(currentUser.id(),
				new CreateCommand(req.kind(), req.provider(), req.label(), req.apiKey(), req.baseUrl()));
	}

	@GetMapping
	ConnectionList list() {
		return new ConnectionList(connections.list(currentUser.id()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID id) {
		connections.delete(currentUser.id(), id);
	}

	/** 20 tests per user per hour (TECHSPEC §5.9) — tests cost the user's own quota. */
	@PostMapping("/{id}/test")
	TestResult test(@PathVariable UUID id, @RequestBody(required = false) TestRequest req) {
		UUID me = currentUser.id();
		Bucket bucket = testBuckets.computeIfAbsent(me, k -> Bucket.builder()
				.addLimit(l -> l.capacity(TESTS_PER_HOUR).refillGreedy(TESTS_PER_HOUR, Duration.ofHours(1))).build());
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many tests",
					"You can test connections " + TESTS_PER_HOUR + " times per hour.");
		}
		return connections.test(me, id, req == null ? null : req.model());
	}

	@GetMapping("/{id}/models")
	ModelList models(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean freeOnly) {
		return new ModelList(connections.models(currentUser.id(), id, freeOnly));
	}

}
