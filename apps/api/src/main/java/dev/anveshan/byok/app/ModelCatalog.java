package dev.anveshan.byok.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ResolvedConnection;
import dev.anveshan.byok.domain.ModelRanking;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.infra.ByokProperties;

/**
 * A provider's live model list (A-FR-5), cached per connection for {@code anveshan.models.cache-ttl}, and
 * its order by {@link ModelRanking}. The key is only decrypted on a cache miss.
 */
@Component
class ModelCatalog {

	private record CachedModels(Instant at, List<ModelInfo> models) {
	}

	private final KeyVault vault;
	private final ByokProperties props;
	private final Clock clock;
	private final Map<UUID, CachedModels> cache = new ConcurrentHashMap<>();

	ModelCatalog(KeyVault vault, ByokProperties props, Clock clock) {
		this.vault = vault;
		this.props = props;
		this.clock = clock;
	}

	/** The provider's raw list (cached); {@code resolver} is only called on a cache miss. */
	List<ModelInfo> list(UUID connectionId, Supplier<ResolvedConnection> resolver) {
		Instant now = clock.instant();
		CachedModels cached = cache.get(connectionId);
		if (cached != null && cached.at().plus(props.models().cacheTtl()).isAfter(now)) {
			return cached.models();
		}
		ResolvedConnection rc = resolver.get();
		List<ModelInfo> models = List.copyOf(vault.transportFor(rc.preset()).models(rc));
		cache.put(connectionId, new CachedModels(now, models));
		return models;
	}

	/** Chat model ids in the order the key test and default routing should try them. */
	List<String> rankChat(AiProvider provider, List<String> ids) {
		return ModelRanking.rank(ids, props.rankingRules(provider));
	}

	/** The whole list for the picker: chat models first, best first, each flagged chat or not. */
	List<ModelInfo> ranked(AiProvider provider, List<ModelInfo> listed, boolean freeOnly) {
		List<String> ranked = rankChat(provider, listed.stream().map(ModelInfo::id).toList());
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

	int testCandidates() {
		return props.models().testCandidates();
	}

	void evict(UUID connectionId) {
		cache.remove(connectionId);
	}

}
