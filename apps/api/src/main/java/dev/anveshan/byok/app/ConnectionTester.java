package dev.anveshan.byok.app;

import static dev.anveshan.byok.app.TestOutcome.BUSY_NOTE;
import static dev.anveshan.byok.app.TestOutcome.RATE_LIMITED_NOTE;
import static dev.anveshan.byok.app.TestOutcome.failed;
import static dev.anveshan.byok.app.TestOutcome.skippedNote;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.anveshan.byok.app.ConnectionService.TestResult;
import dev.anveshan.byok.app.TestOutcome.Skip;
import dev.anveshan.byok.app.TestOutcome.TestFailure;
import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderCallException;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.app.ProviderModels.ProviderModelNotFoundException;
import dev.anveshan.byok.app.ProviderModels.ProviderRateLimitException;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.domain.AiConnection;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import dev.anveshan.byok.infra.AiConnectionRepository;

/**
 * The key test (A-FR-4): picks the models to try, tries them with fallback, records the result. Only key
 * problems (auth, no credit, unreadable) mark a connection FAILING; a missing model or a provider outage
 * keeps the last status. Not transactional: provider calls happen outside any DB transaction.
 */
@Component
class ConnectionTester {

	private static final Logger log = LoggerFactory.getLogger(ConnectionTester.class);

	/** Provider errors that say "this model can't answer right now", not "this key is wrong". */
	private static final Set<String> TEMPORARY = Set.of("UNREACHABLE", "PROVIDER_UNAVAILABLE");

	/**
	 * What a test tries, best first, and whether the key is already proven: the provider answered the
	 * model-list request made with this key (a wrong key gets 400/401/403 there, never a list).
	 */
	record TestPlan(List<String> models, boolean keyProven) {
	}

	private final AiConnectionRepository connections;
	private final KeyVault vault;
	private final ModelCatalog catalog;
	private final SlotService slots;
	private final Clock clock;

	ConnectionTester(AiConnectionRepository connections, KeyVault vault, ModelCatalog catalog, SlotService slots,
			Clock clock) {
		this.connections = connections;
		this.vault = vault;
		this.catalog = catalog;
		this.slots = slots;
		this.clock = clock;
	}

	/**
	 * Without {@code explicit} the backend picks: routed models → preset hint → ranked live list, trying up
	 * to {@code anveshan.models.test-candidates}.
	 */
	TestResult test(AiConnection c, String explicit) {
		ResolvedConnection rc = resolveOrFail(c);
		LlmTransport transport = vault.transportFor(rc.preset());
		try {
			if (c.getKind() == ConnectionKind.SEARCH) {
				long t0 = System.nanoTime();
				transport.test(rc, null);
				save(c, ConnectionStatus.VERIFIED, null);
				return new TestResult(ConnectionStatus.VERIFIED, msSince(t0), null, null, false);
			}
			List<String> routed = explicit != null ? List.of() : slots.modelsRoutedTo(c.getOwnerId(), c.getId());
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
			throw failed(TestFailure.of(e), e.getMessage());
		}
		catch (ProviderException e) {
			// status + fixed text only (never the provider body): enough to see why a real key test failed
			log.warn("Key test failed for {} connection: {} ({})", c.getProvider(), e.code(), e.getMessage());
			throw failed(TestFailure.of(e), e.getMessage()); // the provider failed, not the key
		}
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
				// 5xx ("model overloaded" — common on free tiers) or a network error on this model: the key
				// isn't the problem, another model may answer right now
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
		throw explicit ? failed(TestFailure.MODEL_NOT_FOUND, "Model not found")
				: failed(TestFailure.NO_CHAT_MODEL, "No usable chat model found for this key");
	}

	/**
	 * What a test without an explicit model tries, best first: the models this connection is routed to →
	 * the preset's known-good hint (only if the provider lists it, or lists nothing — so a retired hint
	 * can't break the test) → the live list ranked by {@link dev.anveshan.byok.domain.ModelRanking}.
	 */
	private TestPlan candidates(List<String> routed, AiConnection c, ResolvedConnection rc) {
		LinkedHashSet<String> out = new LinkedHashSet<>(routed);
		String hint = rc.preset().testModel();
		List<String> listed;
		boolean keyProven = false;
		try {
			listed = catalog.list(c.getId(), () -> rc).stream().map(ModelInfo::id).toList();
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
		out.addAll(catalog.rankChat(c.getProvider(), listed));
		return new TestPlan(out.stream().limit(catalog.testCandidates()).toList(), keyProven);
	}

	/** Decrypts the key; an unreadable one marks the connection FAILING. */
	ResolvedConnection resolveOrFail(AiConnection c) {
		try {
			return vault.resolve(c.getId(), c.getOwnerId(), c.getProvider(), c.getBaseUrl(), c.getKeyCiphertext());
		}
		catch (KeyVault.KeyUnreadableException e) {
			save(c, ConnectionStatus.FAILING, e.getMessage());
			throw failed(TestFailure.KEY_UNREADABLE, e.getMessage());
		}
	}

	private boolean fillSlots(AiConnection c, String model) {
		return c.getKind() == ConnectionKind.LLM && slots.fillDefaultsIfEmpty(c.getOwnerId(), c.getId(), model);
	}

	private void save(AiConnection c, ConnectionStatus status, String detail) {
		c.recordTest(status, detail, clock.instant());
		connections.save(c);
	}

	private static long msSince(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

}
