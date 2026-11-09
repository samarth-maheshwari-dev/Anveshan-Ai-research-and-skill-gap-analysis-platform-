package dev.anveshan.sources.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorsTest {

	@Test
	@DisplayName("T-03-02 meanPool ignores padding tokens (mask = 0)")
	void meanPoolIgnoresPadding() {
		float[][] tokens = { { 1, 2 }, { 3, 4 }, { 100, 100 } };

		float[] pooled = Vectors.meanPool(tokens, new long[] { 1, 1, 0 });

		assertThat(pooled).containsExactly(2f, 3f);
	}

	@Test
	@DisplayName("T-03-02 l2Normalize gives length 1; cosine of identical vectors is 1")
	void normalize() {
		float[] v = Vectors.l2Normalize(new float[] { 3, 4 });

		assertThat(v).containsExactly(0.6f, 0.8f);
		assertThat(Vectors.norm(v)).isCloseTo(1.0, within(1e-6));
		assertThat(Vectors.cosine(v, v)).isCloseTo(1.0, within(1e-6));
	}

}
