package dev.anveshan.byok.infra;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ProviderPreset;

/** Provider presets from {@code anveshan.providers.*} (TECHSPEC §10.1). */
@ConfigurationProperties("anveshan")
public record ByokProperties(Map<AiProvider, ProviderPreset> providers) {

	public ByokProperties {
		providers = providers == null ? Map.of() : new EnumMap<>(providers);
		for (AiProvider p : AiProvider.values()) {
			if (!providers.containsKey(p)) {
				throw new IllegalStateException("Missing preset anveshan.providers." + p);
			}
			if (p != AiProvider.CUSTOM && providers.get(p).baseUrl() == null) {
				throw new IllegalStateException("anveshan.providers." + p + ".base-url is required");
			}
		}
	}

	public ProviderPreset preset(AiProvider provider) {
		return providers.get(provider);
	}

}
