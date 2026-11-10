package dev.anveshan.byok.infra;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ModelRanking;
import dev.anveshan.byok.domain.ProviderPreset;

/**
 * Provider presets from {@code anveshan.providers.*} (TECHSPEC §10.1), the model-ranking rules from
 * {@code anveshan.model-ranking.*} and the model-list settings from {@code anveshan.models.*}. Regexes
 * are compiled here, so a bad one stops startup.
 */
@ConfigurationProperties("anveshan")
public record ByokProperties(Map<AiProvider, ProviderPreset> providers, ModelRankingConfig modelRanking,
		ModelsConfig models) {

	/** Global ranking rules: which ids are not chat models, which names mean "pre-release". */
	public record ModelRankingConfig(List<String> exclude, List<String> unstable) {
	}

	/** How long a live model list is cached, and how many models a test without an explicit model tries. */
	public record ModelsConfig(Duration cacheTtl, int testCandidates) {
	}

	public ByokProperties {
		providers = providers == null ? Map.of() : new EnumMap<>(providers);
		modelRanking = modelRanking == null ? new ModelRankingConfig(List.of(), List.of()) : modelRanking;
		if (models == null || models.cacheTtl() == null || models.testCandidates() < 1) {
			throw new IllegalStateException("anveshan.models.cache-ttl and .test-candidates (>= 1) are required");
		}
		for (AiProvider p : AiProvider.values()) {
			if (!providers.containsKey(p)) {
				throw new IllegalStateException("Missing preset anveshan.providers." + p);
			}
			if (p != AiProvider.CUSTOM && providers.get(p).baseUrl() == null) {
				throw new IllegalStateException("anveshan.providers." + p + ".base-url is required");
			}
			// compile now (constructor parameters — fields aren't assigned yet): an invalid regex fails at startup
			ModelRanking.Rules.of(modelRanking.exclude(), modelRanking.unstable(), providers.get(p).preferredModels());
		}
	}

	public ProviderPreset preset(AiProvider provider) {
		return providers.get(provider);
	}

	/** Ranking rules for one provider: global exclude/unstable + the preset's preferred patterns. */
	public ModelRanking.Rules rankingRules(AiProvider provider) {
		return ModelRanking.Rules.of(modelRanking.exclude(), modelRanking.unstable(),
				providers.get(provider).preferredModels());
	}

}
