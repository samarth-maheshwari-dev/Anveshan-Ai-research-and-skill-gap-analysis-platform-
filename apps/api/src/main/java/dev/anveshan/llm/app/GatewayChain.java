package dev.anveshan.llm.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.anveshan.llm.api.AllRoutesExhaustedException;
import dev.anveshan.llm.api.CallContext;
import dev.anveshan.llm.api.NoModelForSlotException;
import dev.anveshan.llm.api.RequestTooLargeException;
import dev.anveshan.llm.api.TaskSlot;
import dev.anveshan.llm.app.ProviderModels.ChatMessage;
import dev.anveshan.llm.app.ProviderModels.CompletionOptions;
import dev.anveshan.llm.app.ProviderModels.ProviderAuthException;
import dev.anveshan.llm.app.ProviderModels.ProviderException;
import dev.anveshan.llm.app.ProviderModels.ProviderRateLimitException;
import dev.anveshan.llm.app.ProviderModels.ProviderRequestTooLargeException;
import dev.anveshan.llm.app.ProviderModels.RawCompletion;
import dev.anveshan.llm.domain.EndpointBreaker;
import dev.anveshan.llm.domain.LlmEnums.LlmCallStatus;
import dev.anveshan.llm.infra.LlmCallLog;
import dev.anveshan.llm.infra.LlmProperties;
import dev.anveshan.shared.security.Secret;

/**
 * The developer-configured endpoint chain ({@code anveshan.llm.endpoints}): tries each configured endpoint in order,
 * skips endpoints whose breaker is open, moves on after a 429 / 5xx / timeout / rejected key. When every endpoint
 * failed or is cooling down it throws {@link AllRoutesExhaustedException}, so a research run pauses and resumes later.
 * Keys are never logged; only the endpoint name and model reach the log and {@code llm_call}.
 */
@Component
public class GatewayChain {

	private static final Logger log = LoggerFactory.getLogger(GatewayChain.class);

	/** A successful answer and the endpoint + model that produced it. */
	public record Routed(RawCompletion completion, String endpoint, String model) {
	}

	private final LlmProperties props;
	private final LlmTransport transport;
	private final LlmCallLog callLog;
	private final Clock clock;
	private final Map<String, EndpointBreaker> breakers = new ConcurrentHashMap<>();

	GatewayChain(LlmProperties props, LlmTransport transport, LlmCallLog callLog, Clock clock) {
		this.props = props;
		this.transport = transport;
		this.callLog = callLog;
		this.clock = clock;
	}

	/** At least one endpoint has a key. */
	public boolean anyConfigured() {
		return !props.configured().isEmpty();
	}

	/** Every configured endpoint has an open breaker (false when none is configured). */
	public boolean allOpen() {
		Instant now = clock.instant();
		List<LlmProperties.Endpoint> eps = props.configured();
		return !eps.isEmpty() && eps.stream().allMatch(e -> read(e.name(), b -> b.isOpen(now)));
	}

	public Routed call(UUID owner, TaskSlot slot, CallContext context, List<ChatMessage> messages,
			CompletionOptions options) {
		List<LlmProperties.Endpoint> eps = props.configured();
		if (eps.isEmpty()) {
			throw new NoModelForSlotException(slot);
		}
		Instant earliestRetry = null;
		boolean tooLarge = false;
		for (LlmProperties.Endpoint e : eps) {
			Instant now = clock.instant();
			var open = read(e.name(), b -> b.openUntil(now));
			if (open.isPresent()) {
				earliestRetry = min(earliestRetry, open.get());
				continue;
			}
			String model = e.modelFor(slot);
			try {
				RawCompletion raw = transport.complete(
						new LlmEndpoint(e.name(), e.baseUrl(), Secret.of(e.apiKey()), e.jsonMode()), model, messages,
						options);
				update(e.name(), EndpointBreaker::success);
				return new Routed(raw, e.name(), model);
			}
			catch (ProviderRequestTooLargeException ex) {
				// the request itself is the problem, not the endpoint: no breaker failure
				callLog.record(owner, slot, e.name(), model, ex.latencyMs(), LlmCallStatus.ERROR, null, null, ex.code(),
						context);
				tooLarge = true;
			}
			catch (ProviderException ex) {
				callLog.record(owner, slot, e.name(), model, ex.latencyMs(), statusOf(ex), null, null, ex.code(), context);
				// the code only, never the provider body or the key
				log.warn("{} endpoint {} ({}) failed: {}", slot, e.name(), model, ex.code());
				if (ex instanceof ProviderRateLimitException r) {
					earliestRetry = min(earliestRetry, r.retryAt());
				}
				Instant failedAt = clock.instant();
				update(e.name(), b -> b.failure(failedAt));
			}
		}
		if (tooLarge) {
			// waiting would only repeat this forever: the caller must send less (I-017)
			throw new RequestTooLargeException("The request is too large for the " + slot + " models");
		}
		Instant fallback = clock.instant().plus(props.breaker().openFor());
		throw new AllRoutesExhaustedException(slot, earliestRetry == null ? fallback : earliestRetry);
	}

	private static LlmCallStatus statusOf(ProviderException ex) {
		if (ex instanceof ProviderRateLimitException) {
			return LlmCallStatus.RATE_LIMITED;
		}
		return ex instanceof ProviderAuthException ? LlmCallStatus.AUTH_ERROR : LlmCallStatus.ERROR;
	}

	private EndpointBreaker breaker(String name) {
		return breakers.computeIfAbsent(name,
				k -> new EndpointBreaker(props.breaker().failures(), props.breaker().openFor()));
	}

	private <R> R read(String name, Function<EndpointBreaker, R> f) {
		EndpointBreaker b = breaker(name);
		synchronized (b) {
			return f.apply(b);
		}
	}

	private void update(String name, Consumer<EndpointBreaker> f) {
		EndpointBreaker b = breaker(name);
		synchronized (b) {
			f.accept(b);
		}
	}

	private static Instant min(Instant a, Instant b) {
		return a == null || b.isBefore(a) ? b : a;
	}

}
