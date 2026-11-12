package dev.anveshan.sources.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.anveshan.sources.api.SourceSearch.Candidate;

/**
 * De-duplication (TECHSPEC §11.2 step 2): same DOI → same canonical URL → titles at least 90 % alike (character
 * trigrams, Dice). Merged candidates keep the first one's fields and fill the gaps from the others. Pure.
 */
public final class Dedupe {

	public static final double TITLE_THRESHOLD = 0.9;

	private Dedupe() {
	}

	/** Candidates must already carry canonical URLs and normalized DOIs. First appearance keeps its place. */
	public static List<Candidate> merge(List<Candidate> candidates) {
		List<Candidate> out = new ArrayList<>();
		Map<String, Integer> byDoi = new LinkedHashMap<>();
		Map<String, Integer> byUrl = new LinkedHashMap<>();
		List<Set<String>> grams = new ArrayList<>();
		for (Candidate c : candidates) {
			Integer at = c.doi() != null ? byDoi.get(c.doi()) : null;
			if (at == null && c.url() != null) {
				at = byUrl.get(c.url());
			}
			Set<String> g = trigrams(c.title());
			if (at == null && !g.isEmpty()) {
				for (int i = 0; i < out.size(); i++) {
					if (dice(g, grams.get(i)) >= TITLE_THRESHOLD) {
						at = i;
						break;
					}
				}
			}
			if (at == null) {
				out.add(c);
				grams.add(g);
				at = out.size() - 1;
			}
			else {
				out.set(at, combine(out.get(at), c));
			}
			Candidate kept = out.get(at);
			if (kept.doi() != null) {
				byDoi.putIfAbsent(kept.doi(), at);
			}
			if (c.doi() != null) {
				byDoi.putIfAbsent(c.doi(), at);
			}
			if (kept.url() != null) {
				byUrl.putIfAbsent(kept.url(), at);
			}
			if (c.url() != null) {
				byUrl.putIfAbsent(c.url(), at);
			}
		}
		return out;
	}

	/** How alike two titles are, 0–1 (Dice coefficient over character trigrams of the normalized text). */
	public static double similarity(String a, String b) {
		return dice(trigrams(a), trigrams(b));
	}

	private static Candidate combine(Candidate a, Candidate b) {
		String adapters = a.adapter();
		for (String name : b.adapter().split("\\+")) {
			if (!List.of(adapters.split("\\+")).contains(name)) {
				adapters = adapters + "+" + name;
			}
		}
		return new Candidate(a.title() != null ? a.title() : b.title(), a.url() != null ? a.url() : b.url(),
				a.doi() != null ? a.doi() : b.doi(), longer(a.snippet(), b.snippet()),
				a.published() != null ? a.published() : b.published(), a.type(), adapters, Math.max(a.score(), b.score()));
	}

	private static String longer(String a, String b) {
		if (a == null) {
			return b;
		}
		return b != null && b.length() > a.length() ? b : a;
	}

	private static Set<String> trigrams(String title) {
		Set<String> out = new HashSet<>();
		if (title == null) {
			return out;
		}
		String t = Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
				.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
		if (t.isEmpty()) {
			return out;
		}
		String padded = "  " + t + " ";
		for (int i = 0; i + 3 <= padded.length(); i++) {
			out.add(padded.substring(i, i + 3));
		}
		return out;
	}

	private static double dice(Set<String> a, Set<String> b) {
		if (a.isEmpty() || b.isEmpty()) {
			return 0;
		}
		int common = 0;
		for (String g : a) {
			if (b.contains(g)) {
				common++;
			}
		}
		return 2.0 * common / (a.size() + b.size());
	}

}
