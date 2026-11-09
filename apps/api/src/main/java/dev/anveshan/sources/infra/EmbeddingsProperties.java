package dev.anveshan.sources.infra;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("anveshan.embeddings")
public record EmbeddingsProperties(Path modelDir, @DefaultValue("32") int batchSize, @DefaultValue("2") int threads) {

	public EmbeddingsProperties {
		if (modelDir == null) {
			throw new IllegalArgumentException("anveshan.embeddings.model-dir is required");
		}
		if (batchSize < 1 || threads < 1) {
			throw new IllegalArgumentException("batch-size and threads must be >= 1");
		}
	}

}
