package dev.anveshan.sources.api;

import java.util.List;

/**
 * Text → 384-dim, L2-normalized vectors (multilingual-e5-small).
 *
 * <p>The e5 {@code "query: "} / {@code "passage: "} prefixes are added here and only here
 * (RULES §4, TS-G12). Callers pass plain text and never add a prefix themselves.
 */
public interface Embeddings {

	int DIMENSIONS = 384;

	/** Search inputs: sub-questions, user questions. */
	float[] query(String text);

	/** Stored content: chunks, claims, notes. */
	float[] passage(String text);

	/** Batch version of {@link #passage(String)}; result order matches input order. */
	List<float[]> passages(List<String> texts);

}
