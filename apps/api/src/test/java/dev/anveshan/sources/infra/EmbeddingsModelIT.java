package dev.anveshan.sources.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.sources.api.Embeddings;
import dev.anveshan.sources.app.PrefixedEmbeddings;
import dev.anveshan.sources.domain.Vectors;

/** Runs the real ONNX model (no Spring, no DB). Needs apps/api/scripts/fetch-model.sh. */
class EmbeddingsModelIT {

	static OnnxTextEncoder encoder;
	static Embeddings embeddings;

	static Path modelDir() {
		String env = System.getenv("ANV_EMBEDDINGS_MODEL_DIR");
		return env != null ? Path.of(env)
				: Path.of(System.getProperty("user.home"), ".cache", "anveshan", "models", "multilingual-e5-small");
	}

	@BeforeAll
	static void load() {
		encoder = new OnnxTextEncoder(modelDir(), 2);
		embeddings = new PrefixedEmbeddings(encoder, 32);
	}

	@AfterAll
	static void close() throws Exception {
		encoder.close();
	}

	@Test
	@DisplayName("T-03-02 three sentences → 384-dim vectors, L2 norm ≈ 1.0 ± 1e-3")
	void dimensionsAndNorm() {
		List<float[]> vectors = embeddings.passages(List.of(
				"The cat sleeps on the sofa.",
				"Spring Boot makes Java web services easy.",
				"भारत एक विशाल देश है।"));

		assertThat(vectors).hasSize(3).allSatisfy(v -> {
			assertThat(v).hasSize(Embeddings.DIMENSIONS);
			assertThat(Vectors.norm(v)).isCloseTo(1.0, within(1e-3));
		});
	}

	@Test
	@DisplayName("T-03-03 cos(dog, puppy) > cos(dog, invoice)")
	void similarityMakesSense() {
		float[] dog = embeddings.query("dog");
		double puppy = Vectors.cosine(dog, embeddings.passage("puppy"));
		double invoice = Vectors.cosine(dog, embeddings.passage("invoice"));

		assertThat(puppy).isGreaterThan(invoice);
	}

	@Test
	@DisplayName("T-03-03 multilingual: Hindi 'कुत्ता' (dog) is closer to 'dog' than to 'invoice'")
	void multilingual() {
		float[] kutta = embeddings.query("कुत्ता");
		assertThat(Vectors.cosine(kutta, embeddings.passage("dog")))
				.isGreaterThan(Vectors.cosine(kutta, embeddings.passage("invoice")));
	}

	@Test
	@DisplayName("T-03-02 same text → same vector; batch result equals single result")
	void deterministic() {
		float[] single = embeddings.passage("Vector search finds similar text.");
		float[] inBatch = embeddings.passages(List.of("short", "Vector search finds similar text.")).get(1);

		assertThat(Vectors.cosine(single, inBatch)).isCloseTo(1.0, within(1e-4));
	}

}
