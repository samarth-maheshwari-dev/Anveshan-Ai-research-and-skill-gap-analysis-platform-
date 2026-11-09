package dev.anveshan.sources.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrefixedEmbeddingsTest {

	/** Records exactly what would be sent to the model. */
	static class RecordingEncoder implements TextEncoder {

		final List<List<String>> calls = new ArrayList<>();

		@Override
		public List<float[]> encode(List<String> texts) {
			calls.add(List.copyOf(texts));
			return texts.stream().map(t -> new float[] { t.length() }).toList();
		}

	}

	RecordingEncoder encoder = new RecordingEncoder();
	PrefixedEmbeddings embeddings = new PrefixedEmbeddings(encoder, 32);

	@Test
	@DisplayName("T-03-01 passage(\"x\") sends \"passage: x\", query(\"x\") sends \"query: x\"")
	void addsPrefix() {
		embeddings.passage("x");
		embeddings.query("x");

		assertThat(encoder.calls).containsExactly(List.of("passage: x"), List.of("query: x"));
	}

	@Test
	@DisplayName("T-03-01 a prefix the caller already added is never doubled")
	void neverDoubles() {
		embeddings.passage("passage: x");
		embeddings.query("  QUERY:   x ");
		embeddings.query("passage: x");
		embeddings.passage("query: passage: x");

		assertThat(encoder.calls).containsExactly(
				List.of("passage: x"), List.of("query: x"), List.of("query: x"), List.of("passage: x"));
	}

	@Test
	@DisplayName("T-03-01 passages() prefixes every text, batches by batch size, keeps order")
	void batchesInOrder() {
		var texts = IntStream.range(0, 70).mapToObj(i -> "chunk " + i).toList();

		var vectors = embeddings.passages(texts);

		assertThat(encoder.calls).extracting(List::size).containsExactly(32, 32, 6);
		assertThat(encoder.calls.getFirst().getFirst()).isEqualTo("passage: chunk 0");
		assertThat(encoder.calls.getLast().getLast()).isEqualTo("passage: chunk 69");
		assertThat(vectors).hasSize(70);
	}

	@Test
	@DisplayName("T-03-01 blank text or a bare prefix is rejected")
	void rejectsBlank() {
		assertThatThrownBy(() -> embeddings.query("   ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> embeddings.passage("passage: ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(encoder.calls).isEmpty();
	}

}
