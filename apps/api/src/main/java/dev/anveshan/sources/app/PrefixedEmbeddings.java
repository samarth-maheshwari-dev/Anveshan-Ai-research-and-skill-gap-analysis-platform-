package dev.anveshan.sources.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.anveshan.sources.api.Embeddings;

/**
 * The single place where e5 prefixes are applied (TS-G12). A prefix a caller added by
 * mistake is removed first, so the model never sees {@code "passage: passage: x"}.
 */
public class PrefixedEmbeddings implements Embeddings {

	static final String QUERY = "query: ";
	static final String PASSAGE = "passage: ";
	/** Matched without the trailing space so "passage:" and "passage:  x" are caught too. */
	private static final List<String> KNOWN = List.of(QUERY.strip(), PASSAGE.strip());

	private final TextEncoder encoder;
	private final int batchSize;

	public PrefixedEmbeddings(TextEncoder encoder, int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be >= 1");
		}
		this.encoder = Objects.requireNonNull(encoder);
		this.batchSize = batchSize;
	}

	@Override
	public float[] query(String text) {
		return encoder.encode(List.of(withPrefix(QUERY, text))).getFirst();
	}

	@Override
	public float[] passage(String text) {
		return encoder.encode(List.of(withPrefix(PASSAGE, text))).getFirst();
	}

	@Override
	public List<float[]> passages(List<String> texts) {
		List<String> prefixed = texts.stream().map(t -> withPrefix(PASSAGE, t)).toList();
		List<float[]> out = new ArrayList<>(prefixed.size());
		for (int i = 0; i < prefixed.size(); i += batchSize) {
			out.addAll(encoder.encode(prefixed.subList(i, Math.min(i + batchSize, prefixed.size()))));
		}
		return out;
	}

	static String withPrefix(String prefix, String text) {
		Objects.requireNonNull(text, "text");
		String t = text.strip();
		boolean stripped;
		do {
			stripped = false;
			for (String p : KNOWN) {
				if (t.regionMatches(true, 0, p, 0, p.length())) {
					t = t.substring(p.length()).strip();
					stripped = true;
				}
			}
		} while (stripped);
		if (t.isEmpty()) {
			throw new IllegalArgumentException("text is blank");
		}
		return prefix + t;
	}

}
