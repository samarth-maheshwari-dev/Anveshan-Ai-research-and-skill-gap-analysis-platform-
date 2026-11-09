package dev.anveshan.sources.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.anveshan.sources.api.Embeddings;
import dev.anveshan.sources.app.PrefixedEmbeddings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingsProperties.class)
class EmbeddingsConfig {

	@Bean(destroyMethod = "close")
	OnnxTextEncoder onnxTextEncoder(EmbeddingsProperties props) {
		return new OnnxTextEncoder(props.modelDir(), props.threads());
	}

	@Bean
	Embeddings embeddings(OnnxTextEncoder encoder, EmbeddingsProperties props) {
		return new PrefixedEmbeddings(encoder, props.batchSize());
	}

}
