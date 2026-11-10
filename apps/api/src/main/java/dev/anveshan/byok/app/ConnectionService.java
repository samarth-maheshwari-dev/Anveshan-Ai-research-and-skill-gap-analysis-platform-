package dev.anveshan.byok.app;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.app.ProviderModels.ProviderModelNotFoundException;
import dev.anveshan.byok.app.ProviderModels.ProviderRateLimitException;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.domain.AiConnection;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import dev.anveshan.byok.domain.ModelRanking;
import dev.anveshan.byok.domain.ProviderPreset;
import dev.anveshan.byok.infra.AiConnectionRepository;
import dev.anveshan.byok.infra.ByokProperties;
import dev.anveshan.shared.security.OutboundUrlGuard;
import dev.anveshan.shared.security.Secret;
import dev.anveshan.shared.web.ApiException;
import jakarta.persistence.EntityManager;

/** AI connections: add, list, delete, test, list models (Module A, TECHSPEC §8.5 / §10.7). */
@Service
public class ConnectionService {

	static final String RATE_LIMITED_NOTE = "Key is valid but rate-limited right now";

	/** Machine-readable cause of a failed test, sent as the Problem {@code reason}; the UI words it. */
	public enum TestFailure {

		KEY_INVALID, NO_CREDIT, KEY_UNREADABLE, MODEL_NOT_FOUND, NO_CHAT_MODEL, PROVIDER_UNAVAILABLE, PROVIDER_ERROR;

		static TestFailure of(ProviderException e) {
			return switch (e.code()) {
				case "AUTH_ERROR" -> KEY_INVALID;
				case "PAYMENT_REQUIRED" -> NO_CREDIT;
				case "MODEL_NOT_FOUND" -> MODEL_NOT_FOUND;
				case "UNREACHABLE", "PROVIDER_UNAVAILABLE" -> PROVIDER_UNAVAILABLE;
				default -> PROVIDER_ERROR;
			};
		}

	}

	public record CreateCommand(ConnectionKind kind, AiProvider provider, String label, String apiKey, String baseUrl) {
	}

	/** What the API returns — never the key, only its last 4 characters (AC-A2). */
	public record ConnectionView(UUID id, ConnectionKind kind, AiProvider provider, String label, String baseUrl,
			String keyLast4, ConnectionStatus status, String statusDetail, Instant lastTestedAt, Instant createdAt) {

		static ConnectionView of(AiConnection c) {
			return new ConnectionView(c.getId(), c.getKind(), c.getProvider(), c.getLabel(), c.getBaseUrl(),
					c.getKeyLast4(), c.getStatus(), c.getStatusDetail(), c.getLastTestedAt(), c.getCreatedAt());
		}

	}

	/**
	 * {@code sampleModel}: the model that answered (the backend picks it when none was asked for).
	 * {@code defaultSlotsSet}: this test filled the user's empty task slots with that model (A-FR-6).
	 */
	public record TestResult(ConnectionStatus status, long latencyMs, String sampleModel, String note,
			boolean defaultSlotsSet) {
	}

	private record CachedModels(Instant at, List<ModelInfo> models) {
	}

	private final AiConnectionRepository connections;
	private final EntityManager em;
	private final KeyVault vault;
	private final ByokProperties props;
	private final OutboundUrlGuard guard;
	private final Clock clock;
	private final SlotService slots;
	private final Map<UUID, CachedModels> modelsCache = new ConcurrentHashMap<>();

	ConnectionService(AiConnectionRepository connections, EntityManager em, KeyVault vault, ByokProperties props,
			OutboundUrlGuard guard, Clock clock, SlotService slots) {
		this.slots = slots;
		this.connections = connections;
		this.em = em;
		this.vault = vault;
		this.props = props;
		this.guard = guard;
		this.clock = clock;
	}

	@Transactional
	public ConnectionView create(UUID owner, CreateCommand cmd) {
		if (cmd.kind() == null || cmd.provider() == null) {
			throw ApiException.badRequest("kind and provider are required.");
		}
		boolean search = cmd.provider() == AiProvider.TAVILY;
		if (search != (cmd.kind() == ConnectionKind.SEARCH)) {
			throw ApiException.badRequest("Tavily is a SEARCH key; every other provider is an LLM key.");
		}
		String label = cmd.label() == null ? "" : cmd.label().strip();
		if (label.isEmpty() || label.length() > 80) {
			throw ApiException.badRequest("Label must be 1–80 characters.");
		}
		String key = cmd.apiKey() == null ? "" : cmd.apiKey().strip();
		if (key.length() < 8 || key.length() > 512 || key.chars().anyMatch(Character::isWhitespace)) {
			throw ApiException.badRequest("The API key looks wrong (8–512 characters, no spaces).");
		}
		ProviderPreset preset = props.preset(cmd.provider());
		String baseUrl;
		if (cmd.provider() == AiProvider.CUSTOM) {
			if (cmd.baseUrl() == null || cmd.baseUrl().isBlank()) {
				throw ApiException.badRequest("A base URL is required for a custom provider.");
			}
			URI safe;
			try {
				safe = guard.requireSafe(URI.create(cmd.baseUrl().strip()), OutboundUrlGuard.HTTPS_ONLY);
			}
			catch (IllegalArgumentException e) {
				throw ApiException.badRequest("The base URL is not a valid URL.");
			}
			baseUrl = safe.toString().endsWith("/") ? safe.toString() : safe + "/";
		}
		else {
			baseUrl = preset.baseUrl().toString(); // presets ignore any client-supplied URL
		}

		Secret secret = Secret.of(key);
		UUID id = UUID.randomUUID();
		var entity = new AiConnection(id, owner, cmd.kind(), cmd.provider(), label, baseUrl,
				vault.seal(id, owner, secret), secret.last4());
		em.persist(entity);
		em.flush();
		return ConnectionView.of(entity);
	}

	@Transactional(readOnly = true)
	public List<ConnectionView> list(UUID owner) {
		return connections.findByOwnerIdOrderByCreatedAt(owner).stream().map(ConnectionView::of).toList();
	}

	@Transactional
	public void delete(UUID owner, UUID id) {
		if (connections.deleteByIdAndOwnerId(id, owner) == 0) {
			throw ApiException.notFound();
		}
		modelsCache.remove(id);
	}

	/**
	 * Proves the key works (A-FR-4). Without a model the backend picks: routed models → preset hint →
	 * ranked live list, trying up to {@code anveshan.models.test-candidates}. Only key problems (auth, no
	 * credit, unreadable) mark the connection FAILING; a missing model or a provider outage keeps the last
	 * status.
	 * Not transactional: the provider calls happen outside any DB transaction.
	 */
	public TestResult test(UUID owner, UUID id, String requestedModel) {
		AiConnection c = owned(owner, id);
		ResolvedConnection rc = resolveOrFail(c);
		LlmTransport transport = vault.transportFor(rc.preset());
		String explicit = requestedModel == null || requestedModel.isBlank() ? null : requestedModel.strip();
		try {
			if (c.getKind() == ConnectionKind.SEARCH) {
				long t0 = System.nanoTime();
				transport.test(rc, null);
				save(c, ConnectionStatus.VERIFIED, null);
				return new TestResult(ConnectionStatus.VERIFIED, msSince(t0), null, null, false);
			}
			List<String> routed = explicit != null ? List.of() : slots.modelsRoutedTo(owner, c.getId());
			List<String> candidates = explicit != null ? List.of(explicit) : candidates(routed, c, rc);
			return testModels(c, rc, transport, candidates, explicit != null, routed);
		}
		catch (ProviderRateLimitException e) {
			// e.g. the model list itself was rate-limited: the key is valid, no model is proven yet
			save(c, ConnectionStatus.VERIFIED, RATE_LIMITED_NOTE);
			return new TestResult(ConnectionStatus.VERIFIED, e.latencyMs(), null, RATE_LIMITED_NOTE, false);
		}
		catch (ProviderAuthException e) {
			save(c, ConnectionStatus.FAILING, e.getMessage());
			throw testFailed(TestFailure.of(e), e.getMessage());
		}
		catch (ProviderException e) {
			throw testFailed(TestFailure.of(e), e.getMessage()); // the provider failed, not the key
		}
	}

	/**
	 * Live model list, cached per connection for {@code anveshan.models.cache-ttl} (A-FR-5): chat models
	 * first, best first.
	 */
	public List<ModelInfo> models(UUID owner, UUID id, boolean freeOnly) {
		AiConnection c = owned(owner, id);
		if (c.getKind() == ConnectionKind.SEARCH) {
			return List.of();
		}
		List<ModelInfo> listed;
		try {
			listed = listModels(c, () -> resolveOrFail(c));
		}
		catch (ProviderException e) {
			throw ApiException.unprocessable("CONNECTION_TEST_FAILED", "Couldn't load models", e.getMessage())
					.withProperty("reason", TestFailure.of(e).name());
		}
		List<String> ranked = ModelRanking.rank(listed.stream().map(ModelInfo::id).toList(),
				props.rankingRules(c.getProvider()));
		Map<String, Integer> rank = new HashMap<>();
		for (int i = 0; i < ranked.size(); i++) {
			rank.put(ranked.get(i), i);
		}
		return listed.stream()
				.map(m -> m.withChat(rank.containsKey(m.id())))
				.filter(m -> !freeOnly || m.free())
				.sorted(Comparator.comparingInt(m -> rank.getOrDefault(m.id(), Integer.MAX_VALUE)))
				.toList();
	}

	/**
	 * Tries each candidate once. Not found or rate-limited → next (a free tier can have a 0 limit on one
	 * model while another works); any other error stops the test. If a routed model was skipped, the
	 * result says so — research would still use it until the user changes Task routing.
	 */
	private TestResult testModels(AiConnection c, ResolvedConnection rc, LlmTransport transport, List<String> candidates,
			boolean explicit, List<String> routed) {
		String skippedRouted = null;
		boolean skippedForLimit = false;
		ProviderRateLimitException limited = null;
		String limitedModel = null;
		for (String model : candidates) {
			long t0 = System.nanoTime();
			try {
				transport.test(rc, model);
				String note = skippedRouted == null ? null : skippedNote(skippedRouted, model, skippedForLimit);
				save(c, ConnectionStatus.VERIFIED, note);
				return new TestResult(ConnectionStatus.VERIFIED, msSince(t0), model, note, fillSlots(c, model));
			}
			catch (ProviderModelNotFoundException e) {
				// auth passed, this model just can't be used with this key: try the next one
				skippedRouted = skippedRouted == null && routed.contains(model) ? model : skippedRouted;
			}
			catch (ProviderRateLimitException e) {
				if (skippedRouted == null && routed.contains(model)) {
					skippedRouted = model;
					skippedForLimit = true;
				}
				if (limited == null) {
					limited = e;
					limitedModel = model;
				}
			}
		}
		if (limited != null) {
			save(c, ConnectionStatus.VERIFIED, RATE_LIMITED_NOTE);
			return new TestResult(ConnectionStatus.VERIFIED, limited.latencyMs(), limitedModel, RATE_LIMITED_NOTE,
					fillSlots(c, limitedModel));
		}
		throw explicit ? testFailed(TestFailure.MODEL_NOT_FOUND, "Model not found")
				: testFailed(TestFailure.NO_CHAT_MODEL, "No usable chat model found for this key");
	}

	/**
	 * What a test without an explicit model tries, best first: the models this connection is routed to →
	 * the preset's known-good hint (only if the provider lists it, or lists nothing — so a retired hint
	 * can't break the test) → the live list ranked by {@link ModelRanking}.
	 */
	private List<String> candidates(List<String> routed, AiConnection c, ResolvedConnection rc) {
		LinkedHashSet<String> out = new LinkedHashSet<>(routed);
		String hint = rc.preset().testModel();
		List<String> listed;
		try {
			listed = listModels(c, () -> rc).stream().map(ModelInfo::id).toList();
		}
		catch (ProviderModelNotFoundException e) {
			listed = List.of(); // this endpoint has no model list
		}
		catch (ProviderAuthException e) {
			throw e; // a key problem: stop here
		}
		catch (ProviderException e) {
			// list rate-limited or unavailable: the routed models and the hint can still be tried
			if (out.isEmpty() && hint == null) {
				throw e; // nothing else to try: report the provider problem
			}
			listed = List.of();
		}
		if (hint != null && (listed.isEmpty() || listed.contains(hint))) {
			out.add(hint);
		}
		out.addAll(ModelRanking.rank(listed, props.rankingRules(c.getProvider())));
		return out.stream().limit(props.models().testCandidates()).toList();
	}

	/** The provider's raw list, cached per connection; the key is only decrypted on a cache miss. */
	private List<ModelInfo> listModels(AiConnection c, Supplier<ResolvedConnection> resolver) {
		Instant now = clock.instant();
		CachedModels cached = modelsCache.get(c.getId());
		if (cached != null && cached.at().plus(props.models().cacheTtl()).isAfter(now)) {
			return cached.models();
		}
		ResolvedConnection rc = resolver.get();
		List<ModelInfo> models = List.copyOf(vault.transportFor(rc.preset()).models(rc));
		modelsCache.put(c.getId(), new CachedModels(now, models));
		return models;
	}

	/**
	 * A rate limit is temporary (research falls back on 429 by itself), so it only gets a heads-up; a model
	 * that's gone needs the user to change Task routing.
	 */
	static String skippedNote(String routedModel, String usedModel, boolean rateLimited) {
		return rateLimited
				? routedModel + " is rate-limited right now; " + usedModel + " answered"
				: routedModel + " didn't answer, " + usedModel + " did. Research still uses " + routedModel
						+ " — change it in Task routing";
	}

	private boolean fillSlots(AiConnection c, String model) {
		return c.getKind() == ConnectionKind.LLM && slots.fillDefaultsIfEmpty(c.getOwnerId(), c.getId(), model);
	}

	private AiConnection owned(UUID owner, UUID id) {
		return connections.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
	}

	private ResolvedConnection resolveOrFail(AiConnection c) {
		try {
			return vault.resolve(c.getId(), c.getOwnerId(), c.getProvider(), c.getBaseUrl(), c.getKeyCiphertext());
		}
		catch (KeyVault.KeyUnreadableException e) {
			save(c, ConnectionStatus.FAILING, e.getMessage());
			throw testFailed(TestFailure.KEY_UNREADABLE, e.getMessage());
		}
	}

	private void save(AiConnection c, ConnectionStatus status, String detail) {
		c.recordTest(status, detail, clock.instant());
		connections.save(c);
	}

	private static long msSince(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

	private static ApiException testFailed(TestFailure reason, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CONNECTION_TEST_FAILED", "Connection test failed",
				detail).withProperty("reason", reason.name());
	}

}
