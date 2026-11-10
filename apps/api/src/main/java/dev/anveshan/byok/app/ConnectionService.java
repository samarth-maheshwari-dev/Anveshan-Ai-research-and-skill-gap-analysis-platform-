package dev.anveshan.byok.app;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderCallException;
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

	static final String BUSY_NOTE = "Your key works, but the provider's models are overloaded right now"
			+ " — research will retry them and switch models if needed";

	private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

	/** Provider errors that say "this model can't answer right now", not "this key is wrong". */
	private static final Set<String> TEMPORARY = Set.of("UNREACHABLE", "PROVIDER_UNAVAILABLE");

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
			TestPlan plan = explicit != null ? new TestPlan(List.of(explicit), false) : candidates(routed, c, rc);
			return testModels(c, rc, transport, plan, explicit != null, routed);
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
			// status + fixed text only (never the provider body): enough to see why a real key test failed
			log.warn("Key test failed for {} connection: {} ({})", c.getProvider(), e.code(), e.getMessage());
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
	 * What a test tries, best first, and whether the key is already proven: the provider answered the
	 * model-list request made with this key (a wrong key gets 400/401/403 there, never a list).
	 */
	record TestPlan(List<String> models, boolean keyProven) {
	}

	/**
	 * Tries each candidate once. Not found, rate-limited, overloaded (5xx) or unreachable → next; any other
	 * error stops the test. If nothing answered only because the provider is busy and the model list
	 * already proved the key, the key is VERIFIED with a note — a provider's bad hour must not block
	 * onboarding (research retries and falls back at run time). If a routed model was skipped, the result
	 * says so — research would still use it until the user changes Task routing.
	 */
	private TestResult testModels(AiConnection c, ResolvedConnection rc, LlmTransport transport, TestPlan plan,
			boolean explicit, List<String> routed) {
		log.info("Key test for {} tries {} (key proven by the model list: {})", c.getProvider(), plan.models(),
				plan.keyProven()); // model ids only
		String skippedRouted = null;
		Skip skippedWhy = Skip.GONE;
		ProviderRateLimitException limited = null;
		String limitedModel = null;
		ProviderCallException unavailable = null;
		String unavailableModel = null;
		for (String model : plan.models()) {
			long t0 = System.nanoTime();
			try {
				transport.test(rc, model);
				String note = skippedRouted == null ? null : skippedNote(skippedRouted, model, skippedWhy);
				save(c, ConnectionStatus.VERIFIED, note);
				return new TestResult(ConnectionStatus.VERIFIED, msSince(t0), model, note, fillSlots(c, model));
			}
			catch (ProviderRateLimitException e) {
				if (skippedRouted == null && routed.contains(model)) {
					skippedRouted = model;
					skippedWhy = Skip.RATE_LIMITED;
				}
				if (limited == null) {
					limited = e;
					limitedModel = model;
				}
			}
			catch (ProviderCallException e) {
				// 5xx ("model overloaded" — common for Gemini 2.5 Flash on the free tier) or a network
				// error on this model: the key isn't the problem, another model may answer right now
				if (!TEMPORARY.contains(e.code())) {
					throw e;
				}
				log.warn("Key test: {} model {} unavailable ({}), trying the next one", c.getProvider(), model,
						e.getMessage()); // fixed text with the HTTP status only — never the provider body
				if (skippedRouted == null && routed.contains(model)) {
					skippedRouted = model;
					skippedWhy = Skip.OVERLOADED;
				}
				if (unavailable == null) {
					unavailable = e;
					unavailableModel = model;
				}
			}
			catch (ProviderModelNotFoundException e) {
				// auth passed, this model just can't be used with this key: try the next one
				log.info("Key test: {} model {} not available for this key, trying the next one", c.getProvider(), model);
				skippedRouted = skippedRouted == null && routed.contains(model) ? model : skippedRouted;
			}
		}
		if (limited != null) {
			save(c, ConnectionStatus.VERIFIED, RATE_LIMITED_NOTE);
			return new TestResult(ConnectionStatus.VERIFIED, limited.latencyMs(), limitedModel, RATE_LIMITED_NOTE,
					fillSlots(c, limitedModel));
		}
		if (unavailable != null && plan.keyProven()) {
			// the key works (it got the model list); the provider's models are just overloaded right now
			save(c, ConnectionStatus.VERIFIED, BUSY_NOTE);
			return new TestResult(ConnectionStatus.VERIFIED, unavailable.latencyMs(), null, BUSY_NOTE,
					fillSlots(c, unavailableModel));
		}
		if (unavailable != null) {
			throw unavailable; // nothing answered and the key isn't proven: "couldn't reach", not "no model"
		}
		throw explicit ? testFailed(TestFailure.MODEL_NOT_FOUND, "Model not found")
				: testFailed(TestFailure.NO_CHAT_MODEL, "No usable chat model found for this key");
	}

	/**
	 * What a test without an explicit model tries, best first: the models this connection is routed to →
	 * the preset's known-good hint (only if the provider lists it, or lists nothing — so a retired hint
	 * can't break the test) → the live list ranked by {@link ModelRanking}.
	 */
	private TestPlan candidates(List<String> routed, AiConnection c, ResolvedConnection rc) {
		LinkedHashSet<String> out = new LinkedHashSet<>(routed);
		String hint = rc.preset().testModel();
		List<String> listed;
		boolean keyProven = false;
		try {
			listed = listModels(c, () -> rc).stream().map(ModelInfo::id).toList();
			keyProven = !listed.isEmpty(); // the provider gave this key its model list
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
		return new TestPlan(out.stream().limit(props.models().testCandidates()).toList(), keyProven);
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

	/** Why a routed model was skipped during a test. */
	enum Skip {
		GONE, RATE_LIMITED, OVERLOADED
	}

	/**
	 * A rate limit or an overload is temporary (research falls back by itself), so it only gets a heads-up;
	 * a model that's gone needs the user to change Task routing.
	 */
	static String skippedNote(String routedModel, String usedModel, Skip why) {
		return switch (why) {
			case RATE_LIMITED -> routedModel + " is rate-limited right now; " + usedModel + " answered";
			case OVERLOADED -> routedModel + " is overloaded right now; " + usedModel + " answered";
			case GONE -> routedModel + " didn't answer, " + usedModel + " did. Research still uses " + routedModel
					+ " — change it in Task routing";
		};
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
