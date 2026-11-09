package dev.anveshan.sources.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dev.anveshan.sources.app.PrefixedEmbeddings;

/**
 * T-03-04 throughput check. Not part of the normal build (slow); run with
 * {@code ./mvnw verify -Dit.test=EmbeddingsBenchmarkIT -Danveshan.benchmark=true}.
 */
@EnabledIfSystemProperty(named = "anveshan.benchmark", matches = "true")
class EmbeddingsBenchmarkIT {

	static final int CHUNKS = 320;
	static final int CHUNK_CHARS = 1200; // TECHSPEC §12.5 chunk size

	@Test
	@DisplayName("T-03-04 embed 320 chunks of ~1,200 chars: ≥ 10 chunks/s with 2 threads")
	void throughput() throws Exception {
		List<String> chunks = chunks();
		double twoThreads = measure(chunks, 2);
		int cores = Runtime.getRuntime().availableProcessors();
		double allCores = measure(chunks, cores);

		System.out.printf("%nT-03-04 RESULT: %d chunks | 2 threads: %.1f chunks/s | %d threads: %.1f chunks/s%n%n",
				CHUNKS, twoThreads, cores, allCores);
		assertThat(twoThreads).isGreaterThanOrEqualTo(10.0);
	}

	static double measure(List<String> chunks, int threads) throws Exception {
		try (var encoder = new OnnxTextEncoder(EmbeddingsModelIT.modelDir(), threads)) {
			var embeddings = new PrefixedEmbeddings(encoder, 32);
			embeddings.passages(chunks.subList(0, 32)); // warm-up
			long start = System.nanoTime();
			embeddings.passages(chunks);
			return CHUNKS / ((System.nanoTime() - start) / 1e9);
		}
	}

	/** Deterministic English-like text, ~1,200 characters per chunk. */
	static List<String> chunks() {
		String[] words = ("research evidence model source claim report network learning system data "
				+ "analysis student project university government policy health energy climate water "
				+ "language computer security privacy market economy history science method result "
				+ "experiment theory design user interface performance database index search query "
				+ "the of and to in is that for with as on by from at which this are be it").split(" ");
		Random random = new Random(42);
		List<String> out = new ArrayList<>(CHUNKS);
		for (int c = 0; c < CHUNKS; c++) {
			StringBuilder sb = new StringBuilder(CHUNK_CHARS + 20);
			while (sb.length() < CHUNK_CHARS) {
				sb.append(words[random.nextInt(words.length)]).append(random.nextInt(12) == 0 ? ". " : " ");
			}
			out.add(sb.toString().strip());
		}
		return out;
	}

}
