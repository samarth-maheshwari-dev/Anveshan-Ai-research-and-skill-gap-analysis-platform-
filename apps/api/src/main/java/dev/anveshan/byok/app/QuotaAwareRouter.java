package dev.anveshan.byok.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.AllRoutesExhaustedException;
import dev.anveshan.byok.api.NoModelForSlotException;
import dev.anveshan.byok.api.ProviderUnavailableException;
import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.app.ProviderModels.ProviderAuthException;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.app.ProviderModels.ProviderRateLimitException;
import dev.anveshan.byok.app.ProviderModels.RawCompletion;
import dev.anveshan.byok.app.ProviderModels.ResolvedRoute;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import dev.anveshan.byok.domain.ByokEnums.LlmCallStatus;
import dev.anveshan.byok.infra.AiConnectionRepository;
import dev.anveshan.byok.infra.LlmCallLog;
import dev.anveshan.byok.infra.QuotaTracker;
import dev.anveshan.byok.infra.SlotStore;

/**
 * Tries a slot's routes in fallback order (PRD §15.4, TECHSPEC §10.3): skips routes that are
 * cooling down, records 429s as cooldowns and moves on, marks rejected keys FAILING. Only when every
 * route is cooling does it give up with {@link AllRoutesExhaustedException}.
 */
@Component
public class QuotaAwareRouter {

	/** A successful provider answer and the route that produced it. */
	public record Routed(RawCompletion completion, ResolvedRoute route) {
	}

	private final SlotStore slots;
	private final KeyVault vault;
	private final QuotaTracker quota;
	private final LlmCallLog callLog;
	private final AiConnectionRepository connections;
	private final Clock clock;

	QuotaAwareRouter(SlotStore slots, KeyVault vault, QuotaTracker quota, LlmCallLog callLog,
			AiConnectionRepository connections, Clock clock) {
		this.slots = slots;
		this.vault = vault;
		this.quota = quota;
		this.callLog = callLog;
		this.connections = connections;
		this.clock = clock;
	}

	public Routed call(UUID owner, TaskSlot slot, Function<ResolvedRoute, RawCompletion> attempt) {
		List<SlotStore.RouteRow> routes = slots.usableRoutes(owner, slot);
		if (routes.isEmpty()) {
			throw new NoModelForSlotException(slot);
		}
		Instant earliestRetry = null;
		String lastFailure = null;
		for (SlotStore.RouteRow row : routes) {
			var cooling = quota.activeCooldown(row.connectionId(), row.model());
			if (cooling.isPresent()) {
				earliestRetry = min(earliestRetry, cooling.get());
				continue;
			}
			ResolvedRoute route;
			try {
				route = new ResolvedRoute(
						vault.resolve(row.connectionId(), owner, row.provider(), row.baseUrl(), row.keyCiphertext()),
						row.model(), row.position());
			}
			catch (KeyVault.KeyUnreadableException e) {
				markFailing(owner, row.connectionId(), e.getMessage());
				lastFailure = e.getMessage();
				continue;
			}
			try {
				RawCompletion raw = attempt.apply(route);
				quota.recordSuccess(row.connectionId(), row.model(), raw.tokensIn(), raw.tokensOut(),
						raw.remainingRequests(), raw.remainingTokens());
				return new Routed(raw, route);
			}
			catch (ProviderRateLimitException e) {
				quota.coolDown(row.connectionId(), row.model(), e.retryAt());
				callLog.record(owner, slot, row.connectionId(), row.model(), e.latencyMs(), LlmCallStatus.RATE_LIMITED,
						null, null, e.code());
				earliestRetry = min(earliestRetry, e.retryAt());
			}
			catch (ProviderAuthException e) {
				callLog.record(owner, slot, row.connectionId(), row.model(), e.latencyMs(), LlmCallStatus.AUTH_ERROR,
						null, null, e.code());
				markFailing(owner, row.connectionId(), e.getMessage());
				lastFailure = e.getMessage();
			}
			catch (ProviderException e) {
				callLog.record(owner, slot, row.connectionId(), row.model(), e.latencyMs(), LlmCallStatus.ERROR, null,
						null, e.code());
				lastFailure = e.getMessage();
			}
		}
		if (earliestRetry != null) {
			throw new AllRoutesExhaustedException(slot, earliestRetry);
		}
		throw new ProviderUnavailableException("No AI route worked for " + slot + ": " + lastFailure);
	}

	private void markFailing(UUID owner, UUID connectionId, String detail) {
		connections.findByIdAndOwnerId(connectionId, owner).ifPresent(c -> {
			c.recordTest(ConnectionStatus.FAILING, detail, clock.instant());
			connections.save(c);
		});
	}

	private static Instant min(Instant a, Instant b) {
		return a == null || b.isBefore(a) ? b : a;
	}

}
